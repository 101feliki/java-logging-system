package com.felixkariuki.insurelog.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data before it is written anywhere, so logs can be kept and
 * shared under data-protection rules (for example Kenya's Data Protection Act).
 *
 * <ul>
 *   <li>Emails keep their first letter and domain: {@code j***@example.com}</li>
 *   <li>Phone numbers keep the last 3 digits: {@code *********678}</li>
 *   <li>Card numbers (13 to 19 digits) keep the last 4 digits</li>
 *   <li>Attributes with sensitive names (nationalId, password, pin...) are fully hidden</li>
 * </ul>
 */
public final class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){9,15}(\\d{4})\\b");
    // Kenyan mobile numbers: +2547.., 2547.., 07.., 01.. (with optional spaces)
    private static final Pattern PHONE = Pattern.compile("(?<![\\d])(?:\\+?254|0)[17](?:[ -]?\\d){8}(?![\\d])");

    // Compared after lower-casing and removing everything except letters, so
    // "national_id", "nationalId" and "National ID" all match "nationalid".
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "nationalid", "idnumber", "passport", "passportnumber", "krapin", "password", "pin", "otp",
            "cvv", "token", "secret", "apikey", "dateofbirth", "dob");

    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replace(EMAIL, text, m -> m.group(1) + "***@" + m.group(2));
        out = replace(CARD, out, m -> "*".repeat(Math.max(0, digits(m.group()) - 4)) + m.group(1));
        out = replace(PHONE, out, m -> {
            String d = m.group().replaceAll("\\D", "");
            return "*".repeat(d.length() - 3) + d.substring(d.length() - 3);
        });
        return out;
    }

    public static Map<String, String> maskAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        attributes.forEach((k, v) -> out.put(k, isSensitiveKey(k) ? "[REDACTED]" : mask(v)));
        return out;
    }

    static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""));
    }

    private static int digits(String s) {
        return s.replaceAll("\\D", "").length();
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replace(Pattern p, String text, Replacer r) {
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(r.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
