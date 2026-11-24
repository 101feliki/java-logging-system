package com.felixkariuki.insurelog.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import com.felixkariuki.insurelog.model.LogQuery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP API built on the JDK's HttpServer (no framework needed).
 *
 * <pre>
 * POST /api/logs          one event or an array of events (JSON)
 * GET  /api/logs          search: level, category, eventType, policyNumber, claimId, customerId, since, until, limit
 * GET  /api/logs/export   same filters, format=csv (default) or json, as a download
 * GET  /api/stats         event counts by category and level
 * GET  /metrics           Prometheus metrics (for Grafana)
 * GET  /health            liveness check
 * </pre>
 * If an API key is configured, /api/* requires the header {@code X-API-Key}.
 */
public class ApiServer {

    private static final int MAX_BODY_BYTES = 1_000_000;
    private static final int MAX_BATCH = 500;

    private final InsuranceLogger logger;
    private final String apiKey;
    private final HttpServer server;
    private final Instant startedAt = Instant.now();

    public ApiServer(InsuranceLogger logger, int port, String apiKey) throws IOException {
        this.logger = logger;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2)));
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(1);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.startsWith("/api/") && !authorised(ex)) {
                send(ex, 401, Map.of("error", "Missing or invalid X-API-Key header"));
                return;
            }

            switch (method + " " + path) {
                case "GET /health" -> send(ex, 200, Map.of("status", "ok", "uptimeSeconds",
                        Instant.now().getEpochSecond() - startedAt.getEpochSecond(), "eventsInMemory", logger.store().size()));
                case "GET /metrics" -> sendText(ex, 200, "text/plain; version=0.0.4; charset=utf-8", logger.metrics().scrape());
                case "POST /api/logs" -> ingest(ex);
                case "GET /api/logs" -> send(ex, 200, logger.search(query(ex)));
                case "GET /api/logs/export" -> export(ex);
                case "GET /api/stats" -> send(ex, 200, stats());
                case "GET /" -> send(ex, 200, Map.of("service", "Insurance Logging System",
                        "endpoints", List.of("POST /api/logs", "GET /api/logs", "GET /api/logs/export",
                                "GET /api/stats", "GET /metrics", "GET /health")));
                default -> send(ex, 404, Map.of("error", "Not found: " + method + " " + path));
            }
        } catch (IllegalArgumentException | DateTimeParseException e) {
            safeSend(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.metrics().rejected("server_error");
            safeSend(ex, 500, Map.of("error", "Internal server error"));
        } finally {
            ex.close();
        }
    }

    private void ingest(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body == null) {
            logger.metrics().rejected("too_large");
            send(ex, 413, Map.of("error", "Request body is larger than " + MAX_BODY_BYTES + " bytes"));
            return;
        }

        List<LogEvent> events;
        try {
            JsonNode node = Json.MAPPER.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new IllegalArgumentException("Request body is empty");
            }
            events = node.isArray()
                    ? Json.MAPPER.convertValue(node, new TypeReference<List<LogEvent>>() { })
                    : List.of(Json.MAPPER.treeToValue(node, LogEvent.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            logger.metrics().rejected("bad_json");
            send(ex, 400, Map.of("error", "Body must be a JSON event or array of events"));
            return;
        }
        if (events.size() > MAX_BATCH) {
            logger.metrics().rejected("batch_too_large");
            send(ex, 413, Map.of("error", "At most " + MAX_BATCH + " events per request"));
            return;
        }

        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        logger.metrics().ingestTimer().record(() -> {
            for (int i = 0; i < events.size(); i++) {
                try {
                    ids.add(logger.log(events.get(i)).id());
                } catch (InsuranceLogger.InvalidEventException e) {
                    errors.add(Map.of("index", i, "errors", e.errors()));
                }
            }
        });

        int status = errors.isEmpty() ? 201 : (ids.isEmpty() ? 400 : 207);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", ids.size());
        result.put("rejected", errors.size());
        result.put("ids", ids);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        send(ex, status, result);
    }

    private void export(HttpExchange ex) throws IOException {
        Map<String, String> params = params(ex);
        String format = params.getOrDefault("format", "csv").toLowerCase();
        params.putIfAbsent("limit", String.valueOf(LogQuery.MAX_LIMIT));
        List<LogEvent> events = logger.search(LogQuery.fromParams(params));
        String stamp = Instant.now().toString().replace(":", "-");
        if ("json".equals(format)) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"insurance-logs-" + stamp + ".json\"");
            sendText(ex, 200, "application/json", Json.MAPPER.writeValueAsString(events));
        } else if ("csv".equals(format)) {
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"insurance-logs-" + stamp + ".csv\"");
            sendText(ex, 200, "text/csv; charset=utf-8", CsvExporter.toCsv(events));
        } else {
            throw new IllegalArgumentException("format must be csv or json");
        }
    }

    private Map<String, Object> stats() {
        List<LogEvent> all = logger.search(LogQuery.all(Integer.MAX_VALUE));
        Map<Category, Integer> byCategory = new EnumMap<>(Category.class);
        Map<Level, Integer> byLevel = new EnumMap<>(Level.class);
        for (LogEvent e : all) {
            byCategory.merge(e.category(), 1, Integer::sum);
            byLevel.merge(e.level(), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventsInMemory", all.size());
        out.put("byCategory", byCategory);
        out.put("byLevel", byLevel);
        out.put("oldest", all.isEmpty() ? null : all.get(all.size() - 1).timestamp());
        out.put("newest", all.isEmpty() ? null : all.get(0).timestamp());
        return out;
    }

    private LogQuery query(HttpExchange ex) {
        return LogQuery.fromParams(params(ex));
    }

    private boolean authorised(HttpExchange ex) {
        if (apiKey == null) {
            return true;
        }
        String given = ex.getRequestHeaders().getFirst("X-API-Key");
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> params(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) {
            return out;
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            if (!v.isEmpty()) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** Returns null if the body is over the size limit. */
    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY_BYTES + 1);
            return data.length > MAX_BODY_BYTES ? null : data;
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        sendText(ex, status, "application/json", Json.MAPPER.writeValueAsString(body));
    }

    private static void safeSend(HttpExchange ex, int status, Object body) {
        try {
            send(ex, status, body);
        } catch (IOException | IllegalStateException ignored) {
            // response already started or client disconnected
        }
    }

    private static void sendText(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
