package com.felixkariuki.insurelog.core;

import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import com.felixkariuki.insurelog.model.LogQuery;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/**
 * Main entry point. Validates events, masks personal data, stores them and
 * updates metrics. Use it directly inside a Java application, or run the jar
 * as a server and send events over HTTP.
 *
 * <pre>{@code
 * InsuranceLogger log = InsuranceLogger.create(Path.of("logs"));
 * log.claimSubmitted("CLM-1001", "POL-2024-778", 45_000, "mpesa");
 * }</pre>
 */
public class InsuranceLogger implements AutoCloseable {

    private final LogStore store;
    private final InsuranceMetrics metrics;

    public InsuranceLogger(LogStore store, InsuranceMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public static InsuranceLogger create(Path logDir) {
        return new InsuranceLogger(new LogStore(logDir, 10_000, 90), new InsuranceMetrics());
    }

    /**
     * Logs an event after validation and PII masking.
     *
     * @return the stored (masked) event
     * @throws InvalidEventException if the event is incomplete or too large
     */
    public LogEvent log(LogEvent event) {
        List<String> errors = EventValidator.validate(event);
        if (!errors.isEmpty()) {
            metrics.rejected("validation");
            throw new InvalidEventException(errors);
        }
        LogEvent stored = event.withDefaults()
                .withMessageAndAttributes(PiiMasker.mask(event.message()), PiiMasker.maskAttributes(event.attributes()));
        store.append(stored);
        metrics.record(stored);
        return stored;
    }

    public List<LogEvent> search(LogQuery query) {
        return store.query(query);
    }

    public InsuranceMetrics metrics() {
        return metrics;
    }

    public LogStore store() {
        return store;
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

    public LogEvent systemError(String source, String message) {
        return log(LogEvent.builder(Category.SYSTEM, "SYSTEM_ERROR").level(Level.ERROR)
                .source(source).message(message).build());
    }

    @Override
    public void close() throws IOException {
        store.close();
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
