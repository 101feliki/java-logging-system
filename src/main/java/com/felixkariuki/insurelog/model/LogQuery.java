package com.felixkariuki.insurelog.model;

import java.time.Instant;
import java.util.Map;

/** Filters for searching stored events. Null fields mean "any". */
public record LogQuery(
        Level minLevel,
        Category category,
        String eventType,
        String policyNumber,
        String claimId,
        String customerId,
        Instant since,
        Instant until,
        int limit
) {
    public static final int MAX_LIMIT = 5000;

    public static LogQuery all(int limit) {
        return new LogQuery(null, null, null, null, null, null, null, null, limit);
    }

    /** Builds a query from URL parameters such as {@code ?level=WARN&category=CLAIM&limit=50}. */
    public static LogQuery fromParams(Map<String, String> p) {
        int limit = 100;
        if (p.containsKey("limit")) {
            try {
                limit = Integer.parseInt(p.get("limit"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("limit must be a number");
            }
        }
        return new LogQuery(
                p.containsKey("level") ? Level.valueOf(p.get("level").toUpperCase()) : null,
                p.containsKey("category") ? Category.valueOf(p.get("category").toUpperCase()) : null,
                p.get("eventType"),
                p.get("policyNumber"),
                p.get("claimId"),
                p.get("customerId"),
                p.containsKey("since") ? Instant.parse(p.get("since")) : null,
                p.containsKey("until") ? Instant.parse(p.get("until")) : null,
                Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    public boolean matches(LogEvent e) {
        return (minLevel == null || e.level().isAtLeast(minLevel))
                && (category == null || e.category() == category)
                && (eventType == null || eventType.equalsIgnoreCase(e.eventType()))
                && (policyNumber == null || policyNumber.equals(e.policyNumber()))
                && (claimId == null || claimId.equals(e.claimId()))
                && (customerId == null || customerId.equals(e.customerId()))
                && (since == null || !e.timestamp().isBefore(since))
                && (until == null || e.timestamp().isBefore(until));
    }
}
