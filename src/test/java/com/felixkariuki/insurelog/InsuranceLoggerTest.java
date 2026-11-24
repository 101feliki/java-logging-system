package com.felixkariuki.insurelog;

import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import com.felixkariuki.insurelog.model.LogQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsuranceLoggerTest {

    @TempDir
    Path dir;

    private InsuranceLogger newLogger() {
        return new InsuranceLogger(new LogStore(dir, 1000, 30), new InsuranceMetrics());
    }

    @Test
    void storesMaskedEventsInDailyFile() throws Exception {
        try (InsuranceLogger log = newLogger()) {
            LogEvent stored = log.claimSubmitted("CLM-1", "POL-1", 45_000, "mpesa");
            assertNotNull(stored.id());
            assertEquals("KES", stored.currency());

            log.log(LogEvent.builder(Category.CUSTOMER, "PROFILE_UPDATED")
                    .message("Phone changed to 0712345678").customer("CUS-1")
                    .attributes(Map.of("nationalId", "12345678")).build());
        }
        Path file = dir.resolve("insurance-" + LocalDate.now(ZoneOffset.UTC) + ".log");
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertFalse(lines.get(1).contains("0712345678"), "phone must be masked");
        assertFalse(lines.get(1).contains("12345678\""), "national ID must be redacted");
        assertEquals("CLAIM_SUBMITTED", Json.MAPPER.readValue(lines.get(0), LogEvent.class).eventType());
    }

    @Test
    void rejectsInvalidEvents() {
        try (InsuranceLogger log = newLogger()) {
            InsuranceLogger.InvalidEventException ex = assertThrows(InsuranceLogger.InvalidEventException.class,
                    () -> log.log(new LogEvent(null, null, null, null, "bad type", "", null, null, null, null,
                            null, null, null, null, null)));
            assertEquals(3, ex.errors().size());
            assertTrue(log.metrics().scrape().contains("insurelog_rejected_events_total"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void searchesByFilters() throws Exception {
        try (InsuranceLogger log = newLogger()) {
            log.policyCreated("POL-1", "CUS-1", "Medical", 3_000);
            log.claimSubmitted("CLM-1", "POL-1", 20_000, "mpesa");
            log.claimDecision("CLM-1", "POL-1", false, 20_000, "documents missing");
            log.systemError("mpesa-gateway", "Callback timed out");

            assertEquals(3, log.search(LogQuery.fromParams(Map.of("policyNumber", "POL-1"))).size());
            assertEquals(2, log.search(LogQuery.fromParams(Map.of("category", "claim"))).size());
            List<LogEvent> warnings = log.search(LogQuery.fromParams(Map.of("level", "WARN")));
            assertEquals(2, warnings.size());
            assertEquals(Level.ERROR, warnings.get(0).level(), "newest first");
        }
    }

    @Test
    void reloadsTodaysEventsAfterRestart() throws Exception {
        try (InsuranceLogger log = newLogger()) {
            log.premiumPaid("POL-9", 1_500, "mpesa");
        }
        try (InsuranceLogger log = newLogger()) {
            assertEquals(1, log.search(LogQuery.all(10)).size());
        }
    }

    @Test
    void exportsBusinessMetrics() throws Exception {
        try (InsuranceLogger log = newLogger()) {
            log.claimSubmitted("CLM-1", "POL-1", 10_000, "mpesa");
            log.claimDecision("CLM-1", "POL-1", true, 10_000, null);
            String metrics = log.metrics().scrape();
            assertTrue(metrics.contains("insurelog_events_total{"));
            assertTrue(metrics.contains("insurelog_claims_total{application=\"insurance-logging-system\",outcome=\"approved\"} 1.0"));
            assertTrue(metrics.contains("insurelog_amount_kes_sum"));
            assertTrue(metrics.contains("jvm_memory_used_bytes"));
        }
    }
}
