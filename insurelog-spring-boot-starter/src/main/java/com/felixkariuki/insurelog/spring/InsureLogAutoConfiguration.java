package com.felixkariuki.insurelog.spring;

import com.felixkariuki.insurelog.core.ConsoleSink;
import com.felixkariuki.insurelog.core.EventSink;
import com.felixkariuki.insurelog.core.HttpShipper;
import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.List;

/**
 * Wires InsureLog into any Spring Boot service automatically. Adding the starter
 * dependency is enough; everything can be tuned with {@code insurelog.*} properties.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
})
@ConditionalOnProperty(prefix = "insurelog", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(InsureLogProperties.class)
public class InsureLogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InsuranceMetrics insuranceMetrics(ObjectProvider<MeterRegistry> registry) {
        return new InsuranceMetrics(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${insurelog.server.url:}'.trim() != ''")
    public HttpShipper insureLogHttpShipper(InsureLogProperties props, ObjectProvider<MeterRegistry> registry) {
        InsureLogProperties.Server s = props.getServer();
        HttpShipper shipper = new HttpShipper(s.getUrl(), s.getApiKey(), s.getBatchSize(), s.getFlushInterval(),
                s.getQueueCapacity(), s.getMaxRetries());
        registry.ifAvailable(r -> {
            Gauge.builder("insurelog.shipper.queue.size", shipper, HttpShipper::queueSize)
                    .description("Events waiting to be sent to the InsureLog server").register(r);
            FunctionCounter.builder("insurelog.shipper.sent", shipper, HttpShipper::sentCount)
                    .description("Events delivered to the InsureLog server").register(r);
            FunctionCounter.builder("insurelog.shipper.dropped", shipper, HttpShipper::droppedCount)
                    .description("Events that could not be delivered").register(r);
        });
        return shipper;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "insurelog.file", name = "enabled", havingValue = "true")
    public LogStore insureLogFileStore(InsureLogProperties props) {
        InsureLogProperties.File f = props.getFile();
        return new LogStore(Path.of(f.getDirectory()), f.getMemoryCapacity(), f.getRetentionDays());
    }

    /**
     * The logger every service injects. Built-in sinks (console, file, server) are
     * added from properties, plus any {@link EventSink} beans you define yourself.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public InsuranceLogger insuranceLogger(InsureLogProperties props, InsuranceMetrics metrics,
                                           ObjectProvider<EventSink> sinks, Environment env) {
        String service = props.getServiceName() != null ? props.getServiceName()
                : env.getProperty("spring.application.name", "unknown-service");
        String mdcKey = props.getCorrelation().getMdcKey();

        InsuranceLogger.Builder b = InsuranceLogger.builder()
                .service(service)
                .metrics(metrics)
                .throwOnInvalid(props.isThrowOnInvalid())
                .correlationId(() -> MDC.get(mdcKey));
        if (props.getConsole().isEnabled()) {
            b.sink(new ConsoleSink());
        }
        List<EventSink> extra = sinks.orderedStream().toList();
        extra.forEach(b::sink);
        return b.build();
    }

    // ---------- Correlation IDs across microservices ----------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(FilterRegistrationBean.class)
    @ConditionalOnProperty(prefix = "insurelog.correlation", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class CorrelationFilterConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "insureLogCorrelationFilter")
        FilterRegistrationBean<CorrelationIdFilter> insureLogCorrelationFilter(InsureLogProperties props) {
            InsureLogProperties.Correlation c = props.getCorrelation();
            FilterRegistrationBean<CorrelationIdFilter> reg =
                    new FilterRegistrationBean<>(new CorrelationIdFilter(c.getHeader(), c.getMdcKey()));
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            reg.addUrlPatterns("/*");
            return reg;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.http.client.ClientHttpRequestInterceptor")
    @ConditionalOnProperty(prefix = "insurelog.correlation", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class CorrelationPropagationConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CorrelationIdInterceptor insureLogCorrelationIdInterceptor(InsureLogProperties props) {
            return new CorrelationIdInterceptor(props.getCorrelation().getHeader(), props.getCorrelation().getMdcKey());
        }

        @Bean
        RestTemplateCustomizer insureLogRestTemplateCustomizer(CorrelationIdInterceptor interceptor) {
            return restTemplate -> restTemplate.getInterceptors().add(interceptor);
        }

        @Bean
        RestClientCustomizer insureLogRestClientCustomizer(CorrelationIdInterceptor interceptor) {
            return builder -> builder.requestInterceptor(interceptor);
        }
    }

    // ---------- Actuator health ----------

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "insureLogHealthIndicator")
        InsureLogHealthIndicator insureLogHealthIndicator(InsuranceLogger insuranceLogger) {
            return new InsureLogHealthIndicator(insuranceLogger);
        }
    }
}
