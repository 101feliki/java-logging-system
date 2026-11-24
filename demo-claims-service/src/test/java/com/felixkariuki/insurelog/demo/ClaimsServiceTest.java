package com.felixkariuki.insurelog.demo;

import com.felixkariuki.insurelog.core.EventSink;
import com.felixkariuki.insurelog.model.LogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "insurelog.server.url=")
class ClaimsServiceTest {

    @TestConfiguration
    static class Capture {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();

        @Bean
        EventSink captureSink() {
            return new EventSink() {
                public void accept(LogEvent e) { events.add(e); }
                public String name() { return "capture"; }
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    Capture capture;

    @Test
    void wholeClaimJourneySharesOneCorrelationIdAndMasksPii() {
        RestClient api = RestClient.create("http://localhost:" + port);
        Map<String, Object> body = Map.of("policyNumber", "POL-1", "amount", 30000, "channel", "mpesa",
                "customerPhone", "0712345678");

        @SuppressWarnings("unchecked")
        Map<String, Object> created = api.post().uri("/claims").header("X-Correlation-Id", "journey-7")
                .body(body).retrieve().body(Map.class);
        api.post().uri("/claims/{id}/approve", created.get("claimId")).header("X-Correlation-Id", "journey-7")
                .body(body).retrieve().toBodilessEntity();

        assertThat(capture.events).extracting(LogEvent::eventType)
                .contains("CLAIM_SUBMITTED", "CLAIM_APPROVED");
        assertThat(capture.events).hasSizeGreaterThanOrEqualTo(3);
        assertThat(capture.events).extracting(LogEvent::correlationId).containsOnly("journey-7");
        assertThat(capture.events).extracting(LogEvent::service).containsOnly("claims-service");
        assertThat(capture.events.get(0).message()).doesNotContain("0712345678").contains("*******678");
    }
}
