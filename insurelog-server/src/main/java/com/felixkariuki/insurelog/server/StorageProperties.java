package com.felixkariuki.insurelog.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server settings under {@code insurelog.storage.*} and {@code insurelog.security.*}.
 *
 * @param directory      where daily log files are written
 * @param retentionDays  files older than this are deleted
 * @param memoryCapacity events kept in memory for fast search
 */
@ConfigurationProperties(prefix = "insurelog.storage")
public record StorageProperties(String directory, int retentionDays, int memoryCapacity) {

    public StorageProperties {
        directory = directory == null || directory.isBlank() ? "logs" : directory;
        retentionDays = retentionDays <= 0 ? 90 : retentionDays;
        memoryCapacity = memoryCapacity <= 0 ? 50_000 : memoryCapacity;
    }
}
