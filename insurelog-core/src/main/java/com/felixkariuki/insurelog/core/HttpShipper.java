package com.felixkariuki.insurelog.core;

import com.felixkariuki.insurelog.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends events to the central InsureLog server in the background.
 *
 * <ul>
 *   <li>Never blocks the business code: events go into a bounded in-memory queue.</li>
 *   <li>Sends in batches, either when the batch is full or every flush interval.</li>
 *   <li>If the server is down, unsent batches go back to the front of the queue and are
 *       retried with growing pauses (up to 30 seconds), so a short outage only delays
 *       delivery. Events are dropped (and counted) only when the queue is full.</li>
 *   <li>On shutdown, sends whatever is left in the queue.</li>
 * </ul>
 */
public class HttpShipper implements EventSink {

    private static final Logger LOG = LoggerFactory.getLogger(HttpShipper.class);

    private final URI endpoint;
    private final String apiKey;
    private final int batchSize;
    private final Duration flushInterval;
    private final int maxRetries;
    private final BlockingDeque<LogEvent> queue;
    private final HttpClient http;
    private final Thread worker;
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private volatile boolean running = true;
    private int consecutiveFailures = 0;

    public HttpShipper(String serverUrl, String apiKey, int batchSize, Duration flushInterval, int queueCapacity, int maxRetries) {
        this.endpoint = URI.create(serverUrl.replaceAll("/+$", "") + "/api/logs");
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
        this.flushInterval = flushInterval;
        this.maxRetries = Math.max(0, maxRetries);
        this.queue = new LinkedBlockingDeque<>(Math.max(1, queueCapacity));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.worker = new Thread(this::run, "insurelog-shipper");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void accept(LogEvent event) {
        if (!queue.offer(event)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                LOG.warn("InsureLog queue is full; {} events dropped so far. Is the server at {} reachable?", n, endpoint);
            }
        }
    }

    private void run() {
        List<LogEvent> batch = new ArrayList<>(batchSize);
        long batchStarted = 0;
        while (true) {
            try {
                LogEvent next = queue.poll(100, TimeUnit.MILLISECONDS);
                if (next != null) {
                    if (batch.isEmpty()) {
                        batchStarted = System.currentTimeMillis();
                    }
                    batch.add(next);
                    queue.drainTo(batch, batchSize - batch.size());
                }
            } catch (InterruptedException e) {
                running = false;
            }
            boolean due = !batch.isEmpty() && (batch.size() >= batchSize || !running
                    || System.currentTimeMillis() - batchStarted >= flushInterval.toMillis());
            if (due) {
                boolean delivered = send(batch);
                if (!delivered && running) {
                    requeue(batch);
                    pauseAfterFailure();
                }
                batch.clear();
            }
            if (!running && queue.isEmpty() && batch.isEmpty()) {
                return;
            }
        }
    }

    /** Returns true if the batch was delivered or is permanently undeliverable (bad request). */
    private boolean send(List<LogEvent> batch) {
        String body;
        try {
            body = Json.MAPPER.writeValueAsString(batch);
        } catch (Exception e) {
            LOG.error("Could not serialise InsureLog batch", e);
            dropped.addAndGet(batch.size());
            return true;
        }
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                if (apiKey != null) {
                    req.header("X-API-Key", apiKey);
                }
                HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code < 300 || code == 207) {
                    sent.addAndGet(batch.size());
                    if (consecutiveFailures > 0) {
                        LOG.info("InsureLog server at {} is reachable again", endpoint);
                    }
                    consecutiveFailures = 0;
                    return true;
                }
                if (code >= 400 && code < 500 && code != 429) {
                    LOG.warn("InsureLog server rejected a batch ({}): {}", code, res.body());
                    dropped.addAndGet(batch.size());
                    return true; // retrying a bad request will not help
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (attempt == maxRetries && consecutiveFailures == 0) {
                    LOG.warn("Could not reach InsureLog server at {}: {}. Events will be kept and retried.", endpoint, e.getMessage());
                }
            }
            if (attempt < maxRetries) {
                sleepBackoff(attempt);
            }
        }
        failedBatches.incrementAndGet();
        consecutiveFailures++;
        if (!running) {
            dropped.addAndGet(batch.size()); // shutting down: give up on this batch
            return true;
        }
        return false;
    }

    /** Puts an unsent batch back at the front of the queue, keeping its order. */
    private void requeue(List<LogEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            if (!queue.offerFirst(batch.get(i))) {
                dropped.addAndGet(i + 1); // queue is full: the oldest events of the batch are lost
                return;
            }
        }
    }

    private void pauseAfterFailure() {
        long waitMs = Math.min(30_000L, 1000L * (1L << Math.min(consecutiveFailures, 5)));
        long until = System.currentTimeMillis() + waitMs;
        while (running && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(10_000L, 250L * (1L << attempt)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long sentCount() {
        return sent.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedBatchCount() {
        return failedBatches.get();
    }

    public int queueSize() {
        return queue.size();
    }

    @Override
    public String name() {
        return "http";
    }

    /** Stops accepting new work and sends what is left (waits up to 10 seconds). */
    @Override
    public void close() throws InterruptedException {
        running = false;
        worker.join(10_000);
    }
}
