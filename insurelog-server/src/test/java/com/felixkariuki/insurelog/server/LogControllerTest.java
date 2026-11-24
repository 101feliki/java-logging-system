package com.felixkariuki.insurelog.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "insurelog.storage.directory=${java.io.tmpdir}/insurelog-test-${random.uuid}",
        "insurelog.security.api-key=secret-key"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext
class LogControllerTest {

    private static final String BATCH = """
            [
              {"category":"CLAIM","eventType":"CLAIM_SUBMITTED","message":"Claim from jane@example.com",
               "service":"claims-service","correlationId":"journey-1",
               "claimId":"CLM-1","policyNumber":"POL-1","amount":25000,"channel":"mpesa"},
              {"category":"PAYMENT","eventType":"CLAIM_PAID","message":"Payout sent","service":"payments-service",
               "correlationId":"journey-1","policyNumber":"POL-1","amount":24000,"channel":"mpesa"},
              {"category":"NOPE","eventType":"x","message":""}
            ]""";

    @Autowired
    MockMvc mvc;

    @Test
    void ingestsSearchesTracesAndExports() throws Exception {
        mvc.perform(post("/api/logs").header("X-API-Key", "secret-key").contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1));

        mvc.perform(get("/api/logs").param("service", "claims-service").header("X-API-Key", "secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message").value("Claim from j***@example.com"));

        mvc.perform(get("/api/logs/trace/journey-1").header("X-API-Key", "secret-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].service").value("claims-service"))
                .andExpect(jsonPath("$[1].service").value("payments-service"));

        mvc.perform(get("/api/logs/export").param("format", "csv").param("policyNumber", "POL-1").header("X-API-Key", "secret-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andExpect(content().string(containsString("correlationId")));

        mvc.perform(get("/api/stats").header("X-API-Key", "secret-key"))
                .andExpect(jsonPath("$.byService.claims-service").value(1));

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("insurelog_events_total{")))
                .andExpect(content().string(containsString("service=\"payments-service\"")));
    }

    @Test
    void protectsApiButNotHealthOrMetrics() throws Exception {
        mvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void rejectsBadInput() throws Exception {
        mvc.perform(post("/api/logs").header("X-API-Key", "secret-key").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/logs").param("level", "LOUD").header("X-API-Key", "secret-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown level: LOUD"));
        mvc.perform(get("/api/logs/export").param("format", "pdf").header("X-API-Key", "secret-key"))
                .andExpect(status().isBadRequest());
    }
}
