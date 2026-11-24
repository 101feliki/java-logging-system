package com.felixkariuki.insurelog.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One structured insurance log event.
 *
 * <p>Only {@code category}, {@code eventType} and {@code message} are required.
 * {@code service} and {@code correlationId} are filled in automatically by the
 * Spring Boot starter, so events from many microservices can be traced as one
 * customer journey.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogEvent(
        String id,
        Instant timestamp,
        Level level,
        Category category,
        String eventType,
        String message,
        String service,
        String correlationId,
        String policyNumber,
        String claimId,
        String customerId,
        String product,
        BigDecimal amount,
        String currency,
        String channel,
        Map<String, String> attributes
) {
    /** Returns a copy with id, timestamp, level, currency and attributes filled in where missing. */
    public LogEvent withDefaults() {
        return new LogEvent(
                id != null ? id : UUID.randomUUID().toString(),
                timestamp != null ? timestamp : Instant.now(),
                level != null ? level : Level.INFO,
                category, eventType, message, service, correlationId, policyNumber, claimId, customerId, product,
                amount,
                amount != null && currency == null ? "KES" : currency,
                channel,
                attributes == null ? Map.of() : attributes);
    }

    public LogEvent withMessageAndAttributes(String newMessage, Map<String, String> newAttributes) {
        return new LogEvent(id, timestamp, level, category, eventType, newMessage, service, correlationId,
                policyNumber, claimId, customerId, product, amount, currency, channel, newAttributes);
    }

    /** Fills service and correlation ID only if they are not already set. */
    public LogEvent withContext(String defaultService, String defaultCorrelationId) {
        return new LogEvent(id, timestamp, level, category, eventType, message,
                service != null ? service : defaultService,
                correlationId != null ? correlationId : defaultCorrelationId,
                policyNumber, claimId, customerId, product, amount, currency, channel, attributes);
    }

    public static Builder builder(Category category, String eventType) {
        return new Builder(category, eventType);
    }

    /** Fluent builder for creating events in Java code. */
    public static final class Builder {
        private final Category category;
        private final String eventType;
        private Level level = Level.INFO;
        private String message = "";
        private String service;
        private String correlationId;
        private String policyNumber;
        private String claimId;
        private String customerId;
        private String product;
        private BigDecimal amount;
        private String currency;
        private String channel;
        private Map<String, String> attributes = Map.of();

        private Builder(Category category, String eventType) {
            this.category = category;
            this.eventType = eventType;
        }

        public Builder level(Level v) { this.level = v; return this; }
        public Builder message(String v) { this.message = v; return this; }
        public Builder service(String v) { this.service = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder policy(String v) { this.policyNumber = v; return this; }
        public Builder claim(String v) { this.claimId = v; return this; }
        public Builder customer(String v) { this.customerId = v; return this; }
        public Builder product(String v) { this.product = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder amount(double v) { this.amount = BigDecimal.valueOf(v); return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder channel(String v) { this.channel = v; return this; }
        public Builder attributes(Map<String, String> v) { this.attributes = v; return this; }

        public LogEvent build() {
            return new LogEvent(null, null, level, category, eventType, message, service, correlationId,
                    policyNumber, claimId, customerId, product, amount, currency, channel, attributes).withDefaults();
        }
    }
}
