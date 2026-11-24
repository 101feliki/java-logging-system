package com.felixkariuki.insurelog.server;

import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ServerConfig {

    @Bean(destroyMethod = "close")
    LogStore logStore(StorageProperties props, MeterRegistry registry) {
        LogStore store = new LogStore(Path.of(props.directory()), props.memoryCapacity(), props.retentionDays());
        Gauge.builder("insurelog.store.events", store, LogStore::size)
                .description("Events held in memory for search").register(registry);
        return store;
    }

    @Bean
    InsuranceMetrics insuranceMetrics(MeterRegistry registry) {
        return new InsuranceMetrics(registry);
    }

    /** The server keeps each event's original service and correlation ID. */
    @Bean
    InsuranceLogger insuranceLogger(LogStore store, InsuranceMetrics metrics) {
        return InsuranceLogger.builder().sink(store).metrics(metrics).throwOnInvalid(true).build();
    }

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(@Value("${insurelog.security.api-key:}") String apiKey) {
        FilterRegistrationBean<ApiKeyFilter> reg = new FilterRegistrationBean<>(new ApiKeyFilter(apiKey));
        reg.addUrlPatterns("/api/*");
        return reg;
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("InsureLog Server API")
                .version("2.0.0")
                .description("Collects, searches and exports PII-safe insurance events from all microservices."));
    }
}
