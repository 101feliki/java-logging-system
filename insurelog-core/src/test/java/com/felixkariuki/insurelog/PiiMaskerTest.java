package com.felixkariuki.insurelog;

import com.felixkariuki.insurelog.core.PiiMasker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiMaskerTest {

    @Test
    void masksEmails() {
        assertEquals("Contact j***@example.com today", PiiMasker.mask("Contact jane.doe@example.com today"));
    }

    @Test
    void masksKenyanPhoneNumbers() {
        assertEquals("Call *******678", PiiMasker.mask("Call 0712345678"));
        assertEquals("Call *********678", PiiMasker.mask("Call +254712345678"));
        assertEquals("Call *******678", PiiMasker.mask("Call 0712 345 678"));
    }

    @Test
    void masksCardNumbersButKeepsLastFour() {
        String masked = PiiMasker.mask("Card 4111 1111 1111 1234 declined");
        assertTrue(masked.contains("1234"));
        assertFalse(masked.contains("4111"));
    }

    @Test
    void leavesBusinessIdentifiersAndAmountsAlone() {
        String text = "Claim CLM-5001 on POL-1001 for KES 150000.00";
        assertEquals(text, PiiMasker.mask(text));
    }

    @Test
    void redactsSensitiveAttributeNames() {
        Map<String, String> masked = PiiMasker.maskAttributes(Map.of(
                "national_id", "12345678", "KRA PIN", "A001234567Z", "region", "Nairobi", "contact", "0712345678"));
        assertEquals("[REDACTED]", masked.get("national_id"));
        assertEquals("[REDACTED]", masked.get("KRA PIN"));
        assertEquals("Nairobi", masked.get("region"));
        assertEquals("*******678", masked.get("contact"));
    }
}
