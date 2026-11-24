package com.felixkariuki.insurelog;

import com.felixkariuki.insurelog.core.EventSink;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import com.felixkariuki.insurelog.model.LogQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InsuranceLoggerTest {

    @TempDir
    Path dir;

    @Test
    void storesMaskedEnrichedEventsInDailyFile() throws Exception {
        LogStore store = new LogStore(dir, 1000, 30);
        try (InsuranceLogger log = InsuranceLogger.builder().service("claims-service")
                .correlationId(() -> "corr-123").sink(store).build()) {
            LogEvent stored = log.claimSubmitted("CLM-1", "POL-1", 45_000, "mpesa");
            assertNotNull(stored.id());
            assertEquals("KES", stored.currency());
            assertEquals("claims-service", stored.service());
            assertEquals("corr-123", stored.correlationId());

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
    void keepsServiceAndCorrelationIdAlreadyOnTheEvent() {
        List<LogEvent> captured = new ArrayList<>();
        InsuranceLogger log = InsuranceLogger.builder().service("server").correlationId(() -> "local")
                .sink(sink(captured)).build();
        log.log(LogEvent.builder(Category.PAYMENT, "PREMIUM_PAID").message("ok")
                .service("payments-service").correlationId("upstream-1").build());
        assertEquals("payments-service", captured.get(0).service());
        assertEquals("upstream-1", captured.get(0).correlationId());
    }

    @Test
    void invalidEventsThrowOrAreSkipped() {
        LogEvent bad = new LogEvent(null, null, null, null, "bad type", "", null, null, null, null,
                null, null, null, null, null, null);

        InsuranceLogger strict = InsuranceLogger.builder().build();
        InsuranceLogger.InvalidEventException ex = assertThrows(InsuranceLogger.InvalidEventException.class, () -> strict.log(bad));
        assertEquals(3, ex.errors().size());

        InsuranceLogger lenient = InsuranceLogger.builder().throwOnInvalid(false).build();
        assertNull(lenient.log(bad));
    }

    @Test
    void aBrokenSinkDoesNotBreakTheCaller() {
        List<LogEvent> captured = new ArrayList<>();
        EventSink broken = new EventSink() {
            public void accept(LogEvent e) { throw new IllegalStateException("disk full"); }
            public String name() { return "broken"; }
        };
        InsuranceLogger log = InsuranceLogger.builder().sink(broken).sink(sink(captured)).build();
        assertNotNull(log.premiumPaid("POL-1", 500, "mpesa"));
        assertEquals(1, captured.size());
    }

    @Test
    void searchesByFilters() throws Exception {
        LogStore store = new LogStore(dir, 1000, 30);
        try (InsuranceLogger log = InsuranceLogger.builder().service("svc").sink(store).build()) {
            log.policyCreated("POL-1", "CUS-1", "Medical", 3_000);
            log.claimSubmitted("CLM-1", "POL-1", 20_000, "mpesa");
            log.claimDecision("CLM-1", "POL-1", false, 20_000, "documents missing");
            log.systemError("Callback timed out");

            assertEquals(3, store.query(LogQuery.fromParams(Map.of("policyNumber", "POL-1"))).size());
            assertEquals(2, store.query(LogQuery.fromParams(Map.of("category", "claim"))).size());
            assertEquals(4, store.query(LogQuery.fromParams(Map.of("service", "SVC"))).size());
            List<LogEvent> warnings = store.query(LogQuery.fromParams(Map.of("level", "WARN")));
            assertEquals(2, warnings.size());
            assertEquals(Level.ERROR, warnings.get(0).level(), "newest first");
        }
    }

    @Test
    void reloadsTodaysEventsAfterRestart() throws Exception {
        try (InsuranceLogger log = InsuranceLogger.builder().sink(new LogStore(dir, 1000, 30)).build()) {
            log.premiumPaid("POL-9", 1_500, "mpesa");
        }
        try (LogStore store = new LogStore(dir, 1000, 30)) {
            assertEquals(1, store.query(LogQuery.all(10)).size());
        }
    }

    @Test
    void recordsBusinessMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InsuranceLogger log = InsuranceLogger.builder().service("claims").metrics(new InsuranceMetrics(registry)).build();
        log.claimSubmitted("CLM-1", "POL-1", 10_000, "mpesa");
        log.claimDecision("CLM-1", "POL-1", true, 10_000, null);
        assertEquals(1.0, registry.get("insurelog.claims").tag("outcome", "approved").tag("service", "claims").counter().count());
        assertEquals(2, registry.get("insurelog.amount").summaries().size());
        assertEquals(2.0, registry.get("insurelog.events").counters().stream().mapToDouble(c -> c.count()).sum());
    }

    private static EventSink sink(List<LogEvent> into) {
        return new EventSink() {
            public void accept(LogEvent e) { into.add(e); }
            public String name() { return "memory"; }
        };
    }
}
