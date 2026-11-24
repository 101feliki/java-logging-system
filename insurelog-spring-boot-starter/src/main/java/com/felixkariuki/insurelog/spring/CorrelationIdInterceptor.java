package com.felixkariuki.insurelog.spring;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Adds the current correlation ID to outgoing RestTemplate / RestClient calls,
 * so the next microservice in the chain logs under the same ID.
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    private final String header;
    private final String mdcKey;

    public CorrelationIdInterceptor(String header, String mdcKey) {
        this.header = header;
        this.mdcKey = mdcKey;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        String id = MDC.get(mdcKey);
        if (id != null && !request.getHeaders().containsKey(header)) {
            request.getHeaders().set(header, id);
        }
        return execution.execute(request, body);
    }
}
