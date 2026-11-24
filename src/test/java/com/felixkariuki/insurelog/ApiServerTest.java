package com.felixkariuki.insurelog;

import com.fasterxml.jackson.databind.JsonNode;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.http.ApiServer;
import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerTest {

    @TempDir
    Path dir;

    private InsuranceLogger logger;
    private ApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        logger = new InsuranceLogger(new LogStore(dir, 1000, 30), new InsuranceMetrics());
        server = new ApiServer(logger, 0, "secret-key");
        server.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
        logger.close();
    }

    private HttpResponse<String> call(String method, String path, String body, boolean withKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (withKey) {
            b.header("X-API-Key", "secret-key");
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ingestsSearchesAndExports() throws Exception {
        String batch = """
                [
                  {"category":"CLAIM","eventType":"CLAIM_SUBMITTED","message":"Claim from jane@example.com",
                   "claimId":"CLM-1","policyNumber":"POL-1","amount":25000,"channel":"mpesa"},
                  {"category":"PAYMENT","eventType":"PREMIUM_PAID","message":"Premium received","policyNumber":"POL-1","amount":1200},
                  {"category":"NOPE","eventType":"x","message":""}
                ]""";
        HttpResponse<String> res = call("POST", "/api/logs", batch, true);
        assertEquals(207, res.statusCode());
        JsonNode body = Json.MAPPER.readTree(res.body());
        assertEquals(2, body.get("accepted").asInt());
        assertEquals(1, body.get("rejected").asInt());

        JsonNode found = Json.MAPPER.readTree(call("GET", "/api/logs?category=CLAIM", null, true).body());
        assertEquals(1, found.size());
        assertEquals("Claim from j***@example.com", found.get(0).get("message").asText());

        HttpResponse<String> csv = call("GET", "/api/logs/export?format=csv&policyNumber=POL-1", null, true);
        assertEquals(200, csv.statusCode());
        assertTrue(csv.headers().firstValue("Content-Disposition").orElse("").contains(".csv"));
        assertEquals(3, csv.body().strip().split("\n").length, "header + 2 rows");

        JsonNode stats = Json.MAPPER.readTree(call("GET", "/api/stats", null, true).body());
        assertEquals(2, stats.get("eventsInMemory").asInt());
    }

    @Test
    void requiresApiKeyForApiButNotForMetricsOrHealth() throws Exception {
        assertEquals(401, call("GET", "/api/logs", null, false).statusCode());
        assertEquals(200, call("GET", "/health", null, false).statusCode());
        HttpResponse<String> metrics = call("GET", "/metrics", null, false);
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("jvm_memory_used_bytes"));
    }

    @Test
    void rejectsBadInput() throws Exception {
        assertEquals(400, call("POST", "/api/logs", "{not json", true).statusCode());
        assertEquals(400, call("GET", "/api/logs?level=LOUD", null, true).statusCode());
        assertEquals(400, call("GET", "/api/logs/export?format=pdf", null, true).statusCode());
        assertEquals(404, call("GET", "/nothing", null, true).statusCode());
    }
}
