package com.felixkariuki.insurelog.core;

import com.felixkariuki.insurelog.model.LogEvent;

/**
 * Somewhere events go after they are validated and masked: a local file, the
 * console, the central server, or anything you implement yourself (Kafka,
 * a database, a SIEM...).
 *
 * <p>Implementations must be thread-safe and should never block the caller for long.</p>
 */
public interface EventSink extends AutoCloseable {

    void accept(LogEvent event);

    /** Short name used in metrics and logs, e.g. "file", "console", "http". */
    String name();

    @Override
    default void close() throws Exception {
    }
}
