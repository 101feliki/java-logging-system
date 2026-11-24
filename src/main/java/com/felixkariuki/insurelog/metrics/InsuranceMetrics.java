package com.felixkariuki.insurelog.metrics;

import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.LogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.Locale;
import java.util.Set;

/**
 * Turns log events into Prometheus metrics that Grafana can chart.
 *
 * <pre>
 * insurelog_events_total{category, event_type, level, channel}
 * insurelog_amount_kes{category, event_type, channel}   (count, sum, max, percentiles)
 * insurelog_claims_total{outcome}                       submitted / approved / rejected / paid
 * insurelog_rejected_events_total{reason}               invalid events that were refused
 * insurelog_ingest_seconds                              time to accept and store events
 * </pre>
 * JVM memory, GC, threads, CPU and uptime are exported as well.
 */
public class InsuranceMetrics {

    private static final Set<String> CLAIM_OUTCOMES = Set.of("SUBMITTED", "APPROVED", "REJECTED", "PAID");

    private final PrometheusMeterRegistry registry;
    private final Timer ingestTimer;

    public InsuranceMetrics() {
        this(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    public InsuranceMetrics(PrometheusMeterRegistry registry) {
        this.registry = registry;
        registry.config().commonTags("application", "insurance-logging-system");
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        this.ingestTimer = Timer.builder("insurelog.ingest")
                .description("Time taken to validate, mask and store incoming events")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void record(LogEvent e) {
        Counter.builder("insurelog.events")
                .description("Insurance log events received")
                .tag("category", e.category().name())
                .tag("event_type", e.eventType())
                .tag("level", e.level().name())
                .tag("channel", e.channel() == null ? "unknown" : e.channel().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();

        if (e.amount() != null && (e.currency() == null || "KES".equals(e.currency()))) {
            DistributionSummary.builder("insurelog.amount")
                    .description("Monetary amounts attached to events")
                    .baseUnit("kes")
                    .tag("category", e.category().name())
                    .tag("event_type", e.eventType())
                    .tag("channel", e.channel() == null ? "unknown" : e.channel().toLowerCase(Locale.ROOT))
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
                        .tag("outcome", outcome.toLowerCase(Locale.ROOT))
                        .register(registry)
                        .increment();
            }
        }
    }

    public void rejected(String reason) {
        Counter.builder("insurelog.rejected.events")
                .description("Events refused because they were invalid")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public Timer ingestTimer() {
        return ingestTimer;
    }

    /** Prometheus text format for the /metrics endpoint. */
    public String scrape() {
        return registry.scrape();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
