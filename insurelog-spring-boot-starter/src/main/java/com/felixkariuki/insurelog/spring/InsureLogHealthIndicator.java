package com.felixkariuki.insurelog.spring;

import com.felixkariuki.insurelog.core.EventSink;
import com.felixkariuki.insurelog.core.HttpShipper;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

/**
 * Shows InsureLog's state at {@code /actuator/health} under "insureLog".
 * It always reports UP, so a logging problem never takes the business service
 * out of a load balancer; the details show whether events are getting through.
 */
public class InsureLogHealthIndicator extends AbstractHealthIndicator {

    private final InsuranceLogger logger;

    public InsureLogHealthIndicator(InsuranceLogger logger) {
        this.logger = logger;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("service", String.valueOf(logger.service()))
                .withDetail("sinks", logger.sinks().stream().map(EventSink::name).toList());
        logger.sinks().stream()
                .filter(HttpShipper.class::isInstance)
                .map(HttpShipper.class::cast)
                .findFirst()
                .ifPresent(s -> builder
                        .withDetail("sent", s.sentCount())
                        .withDetail("dropped", s.droppedCount())
                        .withDetail("failedBatches", s.failedBatchCount())
                        .withDetail("queued", s.queueSize()));
    }
}
