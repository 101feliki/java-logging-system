package com.felixkariuki.insurelog.model;

import java.time.Instant;
import java.util.Map;

/** Filters for searching stored events. Null fields mean "any". */
public record LogQuery(
        Level minLevel,
        Category category,
        String eventType,
        String service,
        String correlationId,
        String policyNumber,
        String claimId,
        String customerId,
        Instant since,
        Instant until,
        int limit
) {
    public static final int MAX_LIMIT = 5000;

    public static LogQuery all(int limit) {
        return new LogQuery(null, null, null, null, null, null, null, null, null, null, limit);
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
                p.containsKey("level") ? parseEnum(Level.class, p.get("level"), "level") : null,
                p.containsKey("category") ? parseEnum(Category.class, p.get("category"), "category") : null,
                blankToNull(p.get("eventType")),
                blankToNull(p.get("service")),
                blankToNull(p.get("correlationId")),
                blankToNull(p.get("policyNumber")),
                blankToNull(p.get("claimId")),
                blankToNull(p.get("customerId")),
                p.containsKey("since") ? Instant.parse(p.get("since")) : null,
                p.containsKey("until") ? Instant.parse(p.get("until")) : null,
                Math.max(1, Math.min(limit, MAX_LIMIT)));
    }

    public boolean matches(LogEvent e) {
        return (minLevel == null || e.level().isAtLeast(minLevel))
                && (category == null || e.category() == category)
                && (eventType == null || eventType.equalsIgnoreCase(e.eventType()))
                && (service == null || service.equalsIgnoreCase(e.service()))
                && (correlationId == null || correlationId.equals(e.correlationId()))
                && (policyNumber == null || policyNumber.equals(e.policyNumber()))
                && (claimId == null || claimId.equals(e.claimId()))
                && (customerId == null || customerId.equals(e.customerId()))
                && (since == null || !e.timestamp().isBefore(since))
                && (until == null || e.timestamp().isBefore(until));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown " + name + ": " + value);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
