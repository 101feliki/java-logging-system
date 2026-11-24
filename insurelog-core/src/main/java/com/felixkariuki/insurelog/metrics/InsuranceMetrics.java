package com.felixkariuki.insurelog.metrics;

import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.LogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Locale;
import java.util.Set;

/**
 * Turns log events into Micrometer metrics. With Spring Boot Actuator and
 * {@code micrometer-registry-prometheus} they appear at {@code /actuator/prometheus}
 * for Prometheus and Grafana.
 *
 * <pre>
 * insurelog_events_total{service, category, event_type, level, channel}
 * insurelog_amount_kes{service, category, event_type, channel}   count, sum, max, p50, p95
 * insurelog_claims_total{service, outcome}                       submitted / approved / rejected / paid
 * insurelog_rejected_events_total{reason}                        invalid events that were refused
 * insurelog_ingest_seconds                                       time to validate, mask and store
 * </pre>
 */
public class InsuranceMetrics {

    private static final Set<String> CLAIM_OUTCOMES = Set.of("SUBMITTED", "APPROVED", "REJECTED", "PAID");

    private final MeterRegistry registry;
    private final Timer ingestTimer;

    public InsuranceMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.ingestTimer = Timer.builder("insurelog.ingest")
                .description("Time taken to validate, mask and store events")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        // register the common reasons at zero so dashboards show a flat line instead of "no data"
        for (String reason : new String[]{"validation", "bad_json", "batch_too_large"}) {
            rejectedCounter(reason);
        }
    }

    private Counter rejectedCounter(String reason) {
        return Counter.builder("insurelog.rejected.events")
                .description("Events refused because they were invalid")
                .tag("reason", reason)
                .register(registry);
    }

    /** Metrics kept in memory only, for apps that do not export metrics. */
    public static InsuranceMetrics inMemory() {
        return new InsuranceMetrics(new SimpleMeterRegistry());
    }

    public void record(LogEvent e) {
        String service = e.service() == null ? "unknown" : e.service();
        String channel = e.channel() == null ? "unknown" : e.channel().toLowerCase(Locale.ROOT);

        Counter.builder("insurelog.events")
                .description("Insurance log events")
                .tag("service", service)
                .tag("category", e.category().name())
                .tag("event_type", e.eventType())
                .tag("level", e.level().name())
                .tag("channel", channel)
                .register(registry)
                .increment();

        if (e.amount() != null && (e.currency() == null || "KES".equals(e.currency()))) {
            DistributionSummary.builder("insurelog.amount")
                    .description("Monetary amounts attached to events")
                    .baseUnit("kes")
                    .tag("service", service)
                    .tag("category", e.category().name())
                    .tag("event_type", e.eventType())
                    .tag("channel", channel)
                    .publishPercentiles(0.5, 0.95)
                    .register(registry)
                    .record(e.amount().doubleValue());
        }

        if (e.category() == Category.CLAIM) {
            String type = e.eventType();
            String outcome = type.startsWith("CLAIM_") ? type.substring(6) : type;
            if (CLAIM_OUTCOMES.contains(outcome)) {
                Counter.builder("insurelog.claims")
                        .description("Claims by outcome")
                        .tag("service", service)
                        .tag("outcome", outcome.toLowerCase(Locale.ROOT))
                        .register(registry)
                        .increment();
            }
        }
    }

    public void rejected(String reason) {
        rejectedCounter(reason).increment();
    }

    public Timer ingestTimer() {
        return ingestTimer;
    }

    public MeterRegistry registry() {
        return registry;
    }
}
