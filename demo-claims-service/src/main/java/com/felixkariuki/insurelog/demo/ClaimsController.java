package com.felixkariuki.insurelog.demo;

import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.Level;
import com.felixkariuki.insurelog.model.LogEvent;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny claims API. Each endpoint does its "business work" and records what
 * happened with the injected {@link InsuranceLogger}.
 *
 * <p>{@code /claims/{id}/approve} calls the payout endpoint over HTTP, the way one
 * microservice calls another, to show the correlation ID travelling with the request.</p>
 */
@RestController
public class ClaimsController {

    record ClaimRequest(String policyNumber, double amount, String channel, String customerPhone) { }

    record PayoutRequest(String claimId, String policyNumber, double amount) { }

    private final InsuranceLogger insureLog;
    private final RestClient.Builder restClient;
    private final Environment env;
    private final AtomicInteger claimSeq = new AtomicInteger(7000);
    private volatile RestClient payments;

    public ClaimsController(InsuranceLogger insureLog, RestClient.Builder restClient, Environment env) {
        this.insureLog = insureLog;
        this.restClient = restClient;
        this.env = env;
    }

    /** The "payments service" URL; defaults to this app, resolved once the web server has its port. */
    private RestClient payments() {
        if (payments == null) {
            String url = env.getProperty("demo.payments-url",
                    "http://localhost:" + env.getProperty("local.server.port", env.getProperty("server.port", "8081")));
            payments = restClient.clone().baseUrl(url).build();
        }
        return payments;
    }

    @PostMapping("/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submit(@RequestBody ClaimRequest req) {
        String claimId = "CLM-" + claimSeq.incrementAndGet();
        insureLog.log(LogEvent.builder(Category.CLAIM, "CLAIM_SUBMITTED")
                .claim(claimId).policy(req.policyNumber()).amount(req.amount()).channel(req.channel())
                // the phone number is masked automatically before the event is stored
                .message("Claim submitted, customer phone " + req.customerPhone())
                .build());
        return Map.of("claimId", claimId, "status", "SUBMITTED");
    }

    @PostMapping("/claims/{claimId}/approve")
    public Map<String, Object> approve(@PathVariable String claimId, @RequestBody ClaimRequest req) {
        insureLog.claimDecision(claimId, req.policyNumber(), true, req.amount(), "assessed automatically");
        // call the "payments service"; the correlation ID header is added automatically
        payments().post().uri("/payouts").body(new PayoutRequest(claimId, req.policyNumber(), req.amount()))
                .retrieve().toBodilessEntity();
        return Map.of("claimId", claimId, "status", "APPROVED");
    }

    @PostMapping("/claims/{claimId}/reject")
    public Map<String, Object> reject(@PathVariable String claimId, @RequestBody ClaimRequest req) {
        insureLog.claimDecision(claimId, req.policyNumber(), false, req.amount(), "documents incomplete");
        return Map.of("claimId", claimId, "status", "REJECTED");
    }

    /** Stands in for a separate payments microservice. */
    @PostMapping("/payouts")
    public Map<String, Object> payout(@RequestBody PayoutRequest req) {
        if (Math.random() < 0.05) {
            insureLog.log(LogEvent.builder(Category.PAYMENT, "PAYOUT_FAILED").level(Level.ERROR)
                    .claim(req.claimId()).policy(req.policyNumber()).amount(req.amount()).channel("mpesa")
                    .message("M-Pesa B2C payout timed out").build());
            return Map.of("status", "FAILED");
        }
        insureLog.claimPaid(req.claimId(), req.policyNumber(), req.amount(), "mpesa");
        return Map.of("status", "PAID");
    }
}
