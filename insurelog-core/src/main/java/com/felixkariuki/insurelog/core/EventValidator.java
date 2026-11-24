package com.felixkariuki.insurelog.core;

import com.felixkariuki.insurelog.model.LogEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Checks that an incoming event is complete and within size limits. */
public final class EventValidator {

    public static final int MAX_MESSAGE_LENGTH = 2000;
    public static final int MAX_ATTRIBUTES = 25;
    public static final int MAX_FIELD_LENGTH = 200;
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,59}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private EventValidator() {
    }

    /** Returns a list of problems; an empty list means the event is valid. */
    public static List<String> validate(LogEvent e) {
        List<String> errors = new ArrayList<>();
        if (e == null) {
            return List.of("event is empty");
        }
        if (e.category() == null) {
            errors.add("category is required (POLICY, CLAIM, PAYMENT, UNDERWRITING, CUSTOMER, SECURITY, SYSTEM)");
        }
        if (e.eventType() == null || !EVENT_TYPE.matcher(e.eventType()).matches()) {
            errors.add("eventType is required and must be UPPER_SNAKE_CASE, e.g. CLAIM_SUBMITTED");
        }
        if (e.message() == null || e.message().isBlank()) {
            errors.add("message is required");
        } else if (e.message().length() > MAX_MESSAGE_LENGTH) {
            errors.add("message must be at most " + MAX_MESSAGE_LENGTH + " characters");
        }
        if (e.amount() != null && e.amount().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("amount cannot be negative");
        }
        if (e.currency() != null && !CURRENCY.matcher(e.currency()).matches()) {
            errors.add("currency must be a 3-letter code such as KES");
        }
        for (String[] f : new String[][]{{"service", e.service()}, {"correlationId", e.correlationId()}, {"policyNumber", e.policyNumber()},
                {"claimId", e.claimId()}, {"customerId", e.customerId()}, {"product", e.product()},
                {"channel", e.channel()}}) {
            if (f[1] != null && f[1].length() > MAX_FIELD_LENGTH) {
                errors.add(f[0] + " must be at most " + MAX_FIELD_LENGTH + " characters");
            }
        }
        if (e.attributes() != null && e.attributes().size() > MAX_ATTRIBUTES) {
            errors.add("at most " + MAX_ATTRIBUTES + " attributes are allowed");
        }
        return errors;
    }
}
