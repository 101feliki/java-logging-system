package com.felixkariuki.insurelog.sim;

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
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sends realistic demo traffic to a running server so the Grafana dashboard has
 * something to show: new policies, premiums (M-Pesa, card, bank), failed
 * payments, claims and their decisions, logins and the occasional system error.
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

    /** Sends {@code perSecond} events every second for {@code seconds} seconds (0 = forever). */
    public void run(int perSecond, int seconds) throws Exception {
        System.out.printf("Sending ~%d events/second to %s%s%n", perSecond, endpoint,
                seconds > 0 ? " for " + seconds + "s" : " (Ctrl+C to stop)");
        long end = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : Long.MAX_VALUE;
        long sent = 0;
        while (System.currentTimeMillis() < end) {
            long start = System.currentTimeMillis();
            List<LogEvent> batch = new ArrayList<>();
            for (int i = 0; i < perSecond; i++) {
                batch.add(nextEvent());
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
            if (sent % (perSecond * 10L) < perSecond) {
                System.out.println("Sent " + sent + " events");
            }
            Thread.sleep(Math.max(0, 1000 - (System.currentTimeMillis() - start)));
        }
        System.out.println("Done. Sent " + sent + " events.");
    }

    LogEvent nextEvent() {
        int roll = random.nextInt(100);
        String policy = "POL-" + (1000 + random.nextInt(Math.max(1, policySeq - 999)));
        String product = PRODUCTS[random.nextInt(PRODUCTS.length)];
        String channel = CHANNELS[random.nextInt(CHANNELS.length)];

        if (roll < 15) {
            String newPolicy = "POL-" + (++policySeq);
            return event(Category.POLICY, "POLICY_CREATED", Level.INFO, "New " + product + " policy issued",
                    newPolicy, null, product, money(500, 15_000), channel);
        } else if (roll < 50) {
            return event(Category.PAYMENT, "PREMIUM_PAID", Level.INFO, "Premium received via " + channel,
                    policy, null, product, money(200, 8_000), channel);
        } else if (roll < 58) {
            String reason = random.nextBoolean() ? "insufficient funds" : "request timed out";
            return event(Category.PAYMENT, "PAYMENT_FAILED", Level.WARN, "Payment failed: " + reason,
                    policy, null, product, money(200, 8_000), channel);
        } else if (roll < 72) {
            String claim = "CLM-" + (++claimSeq);
            return event(Category.CLAIM, "CLAIM_SUBMITTED", Level.INFO, "Claim submitted by customer on 0712345678",
                    policy, claim, product, money(2_000, 150_000), channel);
        } else if (roll < 82) {
            boolean approved = random.nextInt(100) < 75;
            String claim = "CLM-" + (5000 + random.nextInt(Math.max(1, claimSeq - 4999)));
            return event(Category.CLAIM, approved ? "CLAIM_APPROVED" : "CLAIM_REJECTED",
                    approved ? Level.INFO : Level.WARN,
                    approved ? "Claim approved after assessment" : "Claim rejected: documents incomplete",
                    policy, claim, product, money(2_000, 150_000), null);
        } else if (roll < 87) {
            String claim = "CLM-" + (5000 + random.nextInt(Math.max(1, claimSeq - 4999)));
            return event(Category.CLAIM, "CLAIM_PAID", Level.INFO, "Claim payout sent via M-Pesa",
                    policy, claim, product, money(2_000, 120_000), "mpesa");
        } else if (roll < 93) {
            boolean failed = random.nextInt(100) < 20;
            return event(Category.SECURITY, failed ? "LOGIN_FAILED" : "LOGIN_SUCCESS", failed ? Level.WARN : Level.INFO,
                    failed ? "Failed login for agent.user@example.com" : "Agent logged in",
                    null, null, null, null, "web");
        } else if (roll < 97) {
            return event(Category.UNDERWRITING, "RISK_ASSESSED", Level.INFO, "Risk score calculated",
                    policy, null, product, null, null);
        } else {
            return event(Category.SYSTEM, "SYSTEM_ERROR", random.nextInt(10) == 0 ? Level.CRITICAL : Level.ERROR,
                    "M-Pesa callback timed out", null, null, null, null, "mpesa");
        }
    }

    private BigDecimal money(int min, int max) {
        return BigDecimal.valueOf(min + random.nextDouble() * (max - min)).setScale(2, RoundingMode.HALF_UP);
    }

    private LogEvent event(Category c, String type, Level level, String message, String policy, String claim,
                           String product, BigDecimal amount, String channel) {
        return new LogEvent(null, null, level, c, type, message, "simulator", policy, claim,
                policy == null ? null : "CUS-" + policy.substring(4), product, amount, null, channel,
                Map.of("region", random.nextBoolean() ? "Nairobi" : "Mombasa"));
    }
}
