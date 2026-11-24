package com.felixkariuki.insurelog.server;

import com.felixkariuki.insurelog.core.LogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes log files older than the retention period, every night at 01:00 UTC. */
@Component
public class RetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionJob.class);

    private final LogStore store;

    public RetentionJob(LogStore store) {
        this.store = store;
    }

    @Scheduled(cron = "0 0 1 * * *", zone = "UTC")
    public void cleanUp() {
        int deleted = store.deleteExpiredFiles();
        if (deleted > 0) {
            LOG.info("Deleted {} expired log file(s)", deleted);
        }
    }
}
