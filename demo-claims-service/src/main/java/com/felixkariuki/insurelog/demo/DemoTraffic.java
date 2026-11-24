package com.felixkariuki.insurelog.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * When {@code demo.traffic.enabled=true}, calls this service's own API every
 * second like real customers would, so there is live data for Grafana.
 */
@Component
@ConditionalOnProperty(prefix = "demo.traffic", name = "enabled", havingValue = "true")
public class DemoTraffic {

    private static final Logger LOG = LoggerFactory.getLogger(DemoTraffic.class);
    private static final String[] CHANNELS = {"mpesa", "mpesa", "card", "bank", "ussd"};

    private final RestClient.Builder builder;
    private final org.springframework.core.env.Environment env;
    private final Random random = new Random();
    private RestClient api;

    public DemoTraffic(RestClient.Builder builder, org.springframework.core.env.Environment env) {
        this.builder = builder;
        this.env = env;
    }

    @Scheduled(fixedRateString = "${demo.traffic.interval-ms:1000}", initialDelay = 5000)
    public void simulateCustomer() {
        if (api == null) {
            api = builder.clone().baseUrl("http://localhost:" + env.getProperty("local.server.port", "8081")).build();
        }
        // one correlation ID for the whole customer journey; the starter adds it to every call below
        MDC.put("correlationId", UUID.randomUUID().toString());
        try {
            String policy = "POL-" + (1000 + random.nextInt(400));
            double amount = Math.round((2_000 + random.nextDouble() * 120_000) * 100) / 100.0;
            Map<String, Object> claim = Map.of("policyNumber", policy, "amount", amount,
                    "channel", CHANNELS[random.nextInt(CHANNELS.length)], "customerPhone", "07" + (10_000_000 + random.nextInt(89_999_999)));

            @SuppressWarnings("unchecked")
            Map<String, Object> created = api.post().uri("/claims").body(claim).retrieve().body(Map.class);
            String claimId = String.valueOf(created.get("claimId"));

            int roll = random.nextInt(100);
            if (roll < 60) {
                api.post().uri("/claims/{id}/approve", claimId).body(claim).retrieve().toBodilessEntity();
            } else if (roll < 80) {
                api.post().uri("/claims/{id}/reject", claimId).body(claim).retrieve().toBodilessEntity();
            }
        } catch (Exception e) {
            LOG.debug("Demo traffic call failed: {}", e.getMessage());
        } finally {
            MDC.remove("correlationId");
        }
    }
}
