package com.felixkariuki.insurelog.core;

import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Main entry point. Validates events, adds the service name and correlation ID,
 * masks personal data, sends the event to every configured sink and updates metrics.
 *
 * <p>In a Spring Boot service the starter creates this bean for you; just inject it:</p>
 * <pre>{@code
 * @Service
 * class ClaimService {
 *     private final InsuranceLogger insureLog;
 *     ClaimService(InsuranceLogger insureLog) { this.insureLog = insureLog; }
 *
 *     void submit(Claim c) {
 *         insureLog.claimSubmitted(c.id(), c.policyNumber(), c.amount(), "mpesa");
 *     }
 * }
 * }</pre>
 *
 * <p>Without Spring, build one yourself:</p>
 * <pre>{@code
 * InsuranceLogger log = InsuranceLogger.builder()
 *         .service("payments-worker")
 *         .sink(new LogStore(Path.of("logs"), 10_000, 90))
 *         .sink(new HttpShipper("http://insurelog:8080", null, 100, Duration.ofSeconds(2), 10_000, 3))
 *         .build();
 * }</pre>
 */
public class InsuranceLogger implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(InsuranceLogger.class);

    private final List<EventSink> sinks;
    private final InsuranceMetrics metrics;
    private final String service;
    private final Supplier<String> correlationIdSupplier;
    private final boolean throwOnInvalid;

    private InsuranceLogger(Builder b) {
        this.sinks = List.copyOf(b.sinks);
        this.metrics = b.metrics != null ? b.metrics : InsuranceMetrics.inMemory();
        this.service = b.service;
        this.correlationIdSupplier = b.correlationIdSupplier != null ? b.correlationIdSupplier : () -> null;
        this.throwOnInvalid = b.throwOnInvalid;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Logs an event after validation, enrichment and PII masking.
     *
     * @return the stored (masked) event, or {@code null} if it was invalid and
     *         {@code throwOnInvalid} is off
     * @throws InvalidEventException if the event is invalid and {@code throwOnInvalid} is on
     */
    public LogEvent log(LogEvent event) {
        List<String> errors = EventValidator.validate(event);
        if (!errors.isEmpty()) {
            metrics.rejected("validation");
            if (throwOnInvalid) {
                throw new InvalidEventException(errors);
            }
            LOG.warn("InsureLog event ignored: {}", String.join("; ", errors));
            return null;
        }
        LogEvent stored = event.withDefaults()
                .withContext(service, correlationIdSupplier.get())
                .withMessageAndAttributes(PiiMasker.mask(event.message()), PiiMasker.maskAttributes(event.attributes()));
        for (EventSink sink : sinks) {
            try {
                sink.accept(stored);
            } catch (RuntimeException e) {
                // a broken sink must never break the business operation
                LOG.warn("InsureLog sink '{}' failed: {}", sink.name(), e.getMessage());
            }
        }
        metrics.record(stored);
        return stored;
    }

    public InsuranceMetrics metrics() {
        return metrics;
    }

    public List<EventSink> sinks() {
        return sinks;
    }

    public String service() {
        return service;
    }

    // ---------- Convenience methods for common insurance events ----------

    public LogEvent policyCreated(String policyNumber, String customerId, String product, double premium) {
        return log(LogEvent.builder(Category.POLICY, "POLICY_CREATED")
                .policy(policyNumber).customer(customerId).product(product).amount(premium)
                .message("Policy " + policyNumber + " created for " + product).build());
    }

    public LogEvent premiumPaid(String policyNumber, double amount, String channel) {
        return log(LogEvent.builder(Category.PAYMENT, "PREMIUM_PAID")
                .policy(policyNumber).amount(amount).channel(channel)
                .message("Premium of KES " + BigDecimal.valueOf(amount).toPlainString() + " received").build());
    }

    public LogEvent paymentFailed(String policyNumber, double amount, String channel, String reason) {
        return log(LogEvent.builder(Category.PAYMENT, "PAYMENT_FAILED").level(Level.WARN)
                .policy(policyNumber).amount(amount).channel(channel)
                .message("Payment failed: " + reason).build());
    }

    public LogEvent claimSubmitted(String claimId, String policyNumber, double amount, String channel) {
        return log(LogEvent.builder(Category.CLAIM, "CLAIM_SUBMITTED")
                .claim(claimId).policy(policyNumber).amount(amount).channel(channel)
                .message("Claim " + claimId + " submitted").build());
    }

    public LogEvent claimDecision(String claimId, String policyNumber, boolean approved, double amount, String note) {
        return log(LogEvent.builder(Category.CLAIM, approved ? "CLAIM_APPROVED" : "CLAIM_REJECTED")
                .level(approved ? Level.INFO : Level.WARN)
                .claim(claimId).policy(policyNumber).amount(amount)
                .message("Claim " + claimId + (approved ? " approved" : " rejected") + (note == null ? "" : ": " + note))
                .build());
    }

    public LogEvent claimPaid(String claimId, String policyNumber, double amount, String channel) {
        return log(LogEvent.builder(Category.CLAIM, "CLAIM_PAID")
                .claim(claimId).policy(policyNumber).amount(amount).channel(channel)
                .message("Claim " + claimId + " paid out").build());
    }

    public LogEvent securityEvent(String eventType, Level level, String message) {
        return log(LogEvent.builder(Category.SECURITY, eventType).level(level).message(message).build());
    }

    public LogEvent systemError(String message) {
        return log(LogEvent.builder(Category.SYSTEM, "SYSTEM_ERROR").level(Level.ERROR).message(message).build());
    }

    /** Flushes and closes every sink (for example sends what the HTTP shipper still has queued). */
    @Override
    public void close() {
        for (EventSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                LOG.warn("Could not close InsureLog sink '{}': {}", sink.name(), e.getMessage());
            }
        }
    }

    public static final class Builder {
        private final List<EventSink> sinks = new ArrayList<>();
        private InsuranceMetrics metrics;
        private String service;
        private Supplier<String> correlationIdSupplier;
        private boolean throwOnInvalid = true;

        public Builder sink(EventSink sink) { this.sinks.add(sink); return this; }
        public Builder metrics(InsuranceMetrics m) { this.metrics = m; return this; }
        public Builder service(String s) { this.service = s; return this; }
        /** Where to read the current request's correlation ID from (for example SLF4J's MDC). */
        public Builder correlationId(Supplier<String> s) { this.correlationIdSupplier = s; return this; }
        /** true (default): invalid events throw. false: they are logged as warnings and skipped. */
        public Builder throwOnInvalid(boolean t) { this.throwOnInvalid = t; return this; }

        public InsuranceLogger build() {
            return new InsuranceLogger(this);
        }
    }

    /** Thrown when an event fails validation. */
    public static class InvalidEventException extends RuntimeException {
        private final List<String> errors;

        public InvalidEventException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }
}
