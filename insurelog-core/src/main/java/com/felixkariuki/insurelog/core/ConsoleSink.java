package com.felixkariuki.insurelog.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.felixkariuki.insurelog.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes each event as one JSON line through SLF4J (logger name {@code insurelog}),
 * so it also reaches whatever log shipper the service already uses
 * (Loki, ELK, CloudWatch, Datadog...).
 */
public class ConsoleSink implements EventSink {

    private static final Logger LOG = LoggerFactory.getLogger("insurelog");

    @Override
    public void accept(LogEvent e) {
        String line;
        try {
            line = Json.MAPPER.writeValueAsString(e);
        } catch (JsonProcessingException ex) {
            line = e.toString();
        }
        switch (e.level()) {
            case DEBUG -> LOG.debug(line);
            case INFO -> LOG.info(line);
            case WARN -> LOG.warn(line);
            case ERROR, CRITICAL -> LOG.error(line);
        }
    }

    @Override
    public String name() {
        return "console";
    }
}
