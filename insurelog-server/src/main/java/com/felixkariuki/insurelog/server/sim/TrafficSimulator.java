package com.felixkariuki.insurelog.server.sim;

import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Sends realistic demo traffic from several pretend microservices (policy,
 * payments, claims, auth, integrations) so the Grafana dashboard fills up.
 * Related events share a correlation ID, like a real customer journey.
 */
public class TrafficSimulator {

    private static final String[] PRODUCTS = {"Medical", "Last Expense", "Personal Accident", "Hospital Cash", "Crop", "Livestock"};
    private static final String[] CHANNELS = {"mpesa", "mpesa", "mpesa", "card", "bank", "ussd"};

    private final Random random = new Random();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final String apiKey;
    private int policySeq = 1000;
    private int claimSeq = 5000;

    public TrafficSimulator(String baseUrl, String apiKey) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/logs");
        this.apiKey = apiKey;
    }

    /** {@code simulate [--url http://localhost:8080] [--rate 5] [--seconds 0] [--api-key KEY]} */
    public static void main(String[] args) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                o.put(args[i].substring(2), args[i + 1]);
            }
        }
        String url = o.getOrDefault("url", System.getenv().getOrDefault("INSURELOG_URL", "http://localhost:8080"));
        String key = o.getOrDefault("api-key", System.getenv("INSURELOG_API_KEY"));
        new TrafficSimulator(url, key).run(Integer.parseInt(o.getOrDefault("rate", "5")),
                Integer.parseInt(o.getOrDefault("seconds", "0")));
    }

    public void run(int perSecond, int seconds) throws Exception {
        System.out.printf("Sending ~%d events/second to %s%s%n", perSecond, endpoint,
                seconds > 0 ? " for " + seconds + "s" : " (Ctrl+C to stop)");
        long end = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : Long.MAX_VALUE;
        long sent = 0;
        while (System.currentTimeMillis() < end) {
            long start = System.currentTimeMillis();
            List<LogEvent> batch = new ArrayList<>();
            while (batch.size() < perSecond) {
                batch.addAll(nextJourney());
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(batch)));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("X-API-Key", apiKey);
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                System.err.println("Server replied " + res.statusCode() + ": " + res.body());
            }
            sent += batch.size();
            if (sent % (perSecond * 10L) < batch.size()) {
                System.out.println("Sent " + sent + " events");
            }
            Thread.sleep(Math.max(0, 1000 - (System.currentTimeMillis() - start)));
        }
        System.out.println("Done. Sent " + sent + " events.");
    }

    /** One customer journey: a few related events from different services, sharing a correlation ID. */
    List<LogEvent> nextJourney() {
        String corr = UUID.randomUUID().toString();
        String product = PRODUCTS[random.nextInt(PRODUCTS.length)];
        String channel = CHANNELS[random.nextInt(CHANNELS.length)];
        String policy = "POL-" + (1000 + random.nextInt(Math.max(1, policySeq - 999)));
        List<LogEvent> out = new ArrayList<>();
        int roll = random.nextInt(100);

        if (roll < 20) {
            String newPolicy = "POL-" + (++policySeq);
            out.add(event("policy-service", corr, Category.UNDERWRITING, "RISK_ASSESSED", Level.INFO, "Risk score calculated", newPolicy, null, product, null, null));
            out.add(event("policy-service", corr, Category.POLICY, "POLICY_CREATED", Level.INFO, "New " + product + " policy issued", newPolicy, null, product, money(500, 15_000), channel));
            out.add(event("payments-service", corr, Category.PAYMENT, "PREMIUM_PAID", Level.INFO, "First premium received via " + channel, newPolicy, null, product, money(500, 15_000), channel));
        } else if (roll < 55) {
            boolean ok = random.nextInt(100) < 85;
            out.add(ok
                    ? event("payments-service", corr, Category.PAYMENT, "PREMIUM_PAID", Level.INFO, "Premium received via " + channel, policy, null, product, money(200, 8_000), channel)
                    : event("payments-service", corr, Category.PAYMENT, "PAYMENT_FAILED", Level.WARN, "Payment failed: insufficient funds on 0712345678", policy, null, product, money(200, 8_000), channel));
        } else if (roll < 80) {
            String claim = "CLM-" + (++claimSeq);
            out.add(event("claims-service", corr, Category.CLAIM, "CLAIM_SUBMITTED", Level.INFO, "Claim submitted by customer", policy, claim, product, money(2_000, 150_000), channel));
            if (random.nextInt(100) < 70) {
                boolean approved = random.nextInt(100) < 75;
                out.add(event("claims-service", corr, Category.CLAIM, approved ? "CLAIM_APPROVED" : "CLAIM_REJECTED",
                        approved ? Level.INFO : Level.WARN, approved ? "Claim approved after assessment" : "Claim rejected: documents incomplete",
                        policy, claim, product, money(2_000, 150_000), null));
                if (approved) {
                    out.add(event("payments-service", corr, Category.CLAIM, "CLAIM_PAID", Level.INFO, "Claim payout sent via M-Pesa", policy, claim, product, money(2_000, 120_000), "mpesa"));
                }
            }
        } else if (roll < 92) {
            boolean failed = random.nextInt(100) < 20;
            out.add(event("auth-service", corr, Category.SECURITY, failed ? "LOGIN_FAILED" : "LOGIN_SUCCESS", failed ? Level.WARN : Level.INFO,
                    failed ? "Failed login for agent.user@example.com" : "Agent logged in", null, null, null, null, "web"));
        } else {
            out.add(event("integration-service", corr, Category.SYSTEM, "SYSTEM_ERROR", random.nextInt(10) == 0 ? Level.CRITICAL : Level.ERROR,
                    "M-Pesa callback timed out", null, null, null, null, "mpesa"));
        }
        return out;
    }

    private BigDecimal money(int min, int max) {
        return BigDecimal.valueOf(min + random.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP);
    }

    private LogEvent event(String service, String corr, Category c, String type, Level level, String message, String policy,
                           String claim, String product, BigDecimal amount, String channel) {
        return new LogEvent(null, null, level, c, type, message, service, corr, policy, claim,
                policy == null ? null : "CUS-" + policy.substring(4), product, amount, null, channel,
                Map.of("region", random.nextBoolean() ? "Nairobi" : "Mombasa"));
    }
}
