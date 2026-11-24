package com.felixkariuki.insurelog.model;

/** Severity of a log event, from least to most severe. */
public enum Level {
    DEBUG, INFO, WARN, ERROR, CRITICAL;

    public boolean isAtLeast(Level other) {
        return compareTo(other) >= 0;
    }
}
