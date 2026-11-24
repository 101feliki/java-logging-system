package com.felixkariuki.insurelog.spring;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every incoming request a correlation ID: reuses the caller's
 * {@code X-Correlation-Id} header if present, otherwise creates one. The ID is
 * put in SLF4J's MDC (so InsureLog events and normal log lines carry it) and
 * echoed back in the response header.
 */
public class CorrelationIdFilter implements Filter {

    // Only accept safe, reasonably sized IDs from callers
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private final String header;
    private final String mdcKey;

    public CorrelationIdFilter(String header, String mdcKey) {
        this.header = header;
        this.mdcKey = mdcKey;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        String incoming = req instanceof HttpServletRequest http ? http.getHeader(header) : null;
        String id = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(mdcKey, id);
        if (res instanceof HttpServletResponse http) {
            http.setHeader(header, id);
        }
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(mdcKey);
        }
    }
}
