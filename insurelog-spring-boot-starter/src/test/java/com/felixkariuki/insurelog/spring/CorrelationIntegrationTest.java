package com.felixkariuki.insurelog.spring;

import com.felixkariuki.insurelog.core.EventSink;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.model.LogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simulates two microservices in one app: /orders calls /payments over HTTP.
 * Both events must carry the same correlation ID.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.application.name=orders-service", "insurelog.console.enabled=false"})
class CorrelationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    CaptureConfig capture;

    @Test
    void propagatesCorrelationIdAcrossServiceCalls() {
        ResponseEntity<String> res = RestClient.create("http://localhost:" + port).get().uri("/orders")
                .header("X-Correlation-Id", "journey-42").retrieve().toEntity(String.class);

        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("journey-42");
        assertThat(res.getBody()).isEqualTo("journey-42");
        assertThat(capture.events).hasSize(2);
        assertThat(capture.events).extracting(LogEvent::correlationId).containsOnly("journey-42");
        assertThat(capture.events).extracting(LogEvent::service).containsOnly("orders-service");
    }

    @Test
    void createsCorrelationIdWhenCallerSendsNone() {
        ResponseEntity<String> res = RestClient.create("http://localhost:" + port).get().uri("/payments")
                .retrieve().toEntity(String.class);
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @org.springframework.context.annotation.Configuration
    static class CaptureConfig {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();

        @Bean
        EventSink capture() {
            return new EventSink() {
                public void accept(LogEvent e) { events.add(e); }
                public String name() { return "capture"; }
            };
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import(CaptureConfig.class)
    @RestController
    static class TestApp {
        @Autowired
        InsuranceLogger insureLog;

        @Autowired
        RestClient.Builder builder;

        @Autowired
        org.springframework.core.env.Environment env;

        @GetMapping("/orders")
        String orders() {
            insureLog.policyCreated("POL-1", "CUS-1", "Medical", 1000);
            String port = env.getProperty("local.server.port");
            return builder.build().get().uri("http://localhost:" + port + "/payments").retrieve().body(String.class);
        }

        @GetMapping("/payments")
        String payments(@RequestHeader(value = "X-Correlation-Id", required = false) String id) {
            insureLog.premiumPaid("POL-1", 1000, "mpesa");
            return id;
        }
    }
}
