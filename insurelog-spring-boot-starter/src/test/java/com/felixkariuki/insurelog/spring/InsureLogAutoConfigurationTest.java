package com.felixkariuki.insurelog.spring;

import com.felixkariuki.insurelog.core.ConsoleSink;
import com.felixkariuki.insurelog.core.EventSink;
import com.felixkariuki.insurelog.core.HttpShipper;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.model.LogEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsureLogAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InsureLogAutoConfiguration.class));

    @TempDir
    Path dir;

    @Test
    void worksWithZeroConfiguration() {
        runner.withPropertyValues("spring.application.name=claims-service").run(ctx -> {
            InsuranceLogger log = ctx.getBean(InsuranceLogger.class);
            assertThat(log.service()).isEqualTo("claims-service");
            assertThat(log.sinks()).hasSize(1).first().isInstanceOf(ConsoleSink.class);
            assertThat(ctx).doesNotHaveBean(HttpShipper.class);
            assertThat(log.premiumPaid("POL-1", 100, "mpesa").service()).isEqualTo("claims-service");
        });
    }

    @Test
    void addsServerShippingAndFileWhenConfigured() {
        runner.withBean(SimpleMeterRegistry.class)
                .withPropertyValues("insurelog.service-name=payments", "insurelog.server.url=http://localhost:1",
                        "insurelog.file.enabled=true", "insurelog.file.directory=" + dir, "insurelog.console.enabled=false")
                .run(ctx -> {
                    InsuranceLogger log = ctx.getBean(InsuranceLogger.class);
                    assertThat(log.sinks()).hasSize(2).hasAtLeastOneElementOfType(HttpShipper.class)
                            .hasAtLeastOneElementOfType(LogStore.class);
                    SimpleMeterRegistry registry = ctx.getBean(SimpleMeterRegistry.class);
                    assertThat(registry.find("insurelog.shipper.queue.size").gauge()).isNotNull();
                    log.claimSubmitted("CLM-1", "POL-1", 5000, "card");
                    assertThat(registry.get("insurelog.events").tag("service", "payments").counter().count()).isEqualTo(1.0);
                });
    }

    @Test
    void usesCustomSinkBeans() {
        runner.withUserConfiguration(CustomSinkConfig.class)
                .withPropertyValues("insurelog.console.enabled=false")
                .run(ctx -> {
                    ctx.getBean(InsuranceLogger.class).systemError("boom");
                    assertThat(ctx.getBean(CustomSinkConfig.class).received).hasSize(1);
                });
    }

    @Test
    void blankServerUrlMeansNoShipping() {
        runner.withPropertyValues("insurelog.server.url= ")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HttpShipper.class));
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("insurelog.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(InsuranceLogger.class));
    }

    @Test
    void registersCorrelationFilterInWebApps() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InsureLogAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasBean("insureLogCorrelationFilter");
                    assertThat(ctx.getBean("insureLogCorrelationFilter", FilterRegistrationBean.class).getFilter())
                            .isInstanceOf(CorrelationIdFilter.class);
                    assertThat(ctx).hasSingleBean(CorrelationIdInterceptor.class);
                });
    }

    @Configuration
    static class CustomSinkConfig {
        final List<LogEvent> received = new ArrayList<>();

        @Bean
        EventSink kafkaLikeSink() {
            return new EventSink() {
                public void accept(LogEvent e) { received.add(e); }
                public String name() { return "custom"; }
            };
        }
    }
}
