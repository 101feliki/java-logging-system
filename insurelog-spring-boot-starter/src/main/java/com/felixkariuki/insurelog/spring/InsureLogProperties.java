package com.felixkariuki.insurelog.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings under {@code insurelog.*} in application.yml / application.properties.
 *
 * <pre>
 * insurelog:
 *   service-name: claims-service        # defaults to spring.application.name
 *   server:
 *     url: http://insurelog-server:8080 # send events to the central server
 *     api-key: ${INSURELOG_API_KEY:}
 *   file:
 *     enabled: true                     # also keep local daily files
 * </pre>
 */
@ConfigurationProperties(prefix = "insurelog")
public class InsureLogProperties {

    /** Turn InsureLog off completely. */
    private boolean enabled = true;

    /** Name stamped on every event. Defaults to spring.application.name. */
    private String serviceName;

    /** If true, invalid events throw an exception; if false (default) they are logged as warnings and skipped. */
    private boolean throwOnInvalid = false;

    private final Console console = new Console();
    private final File file = new File();
    private final Server server = new Server();
    private final Correlation correlation = new Correlation();

    public static class Console {
        /** Write each event as a JSON line to the application log (logger "insurelog"). */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class File {
        /** Keep daily JSON-lines files on this service's disk. */
        private boolean enabled = false;
        private String directory = "logs/insurelog";
        private int retentionDays = 30;
        /** Recent events kept in memory for searching. */
        private int memoryCapacity = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getMemoryCapacity() { return memoryCapacity; }
        public void setMemoryCapacity(int memoryCapacity) { this.memoryCapacity = memoryCapacity; }
    }

    public static class Server {
        /** Base URL of the central InsureLog server. Shipping is on when this is set. */
        private String url;
        private String apiKey;
        private int batchSize = 100;
        private Duration flushInterval = Duration.ofSeconds(2);
        /** Events buffered while the server is unreachable before new ones are dropped. */
        private int queueCapacity = 10_000;
        private int maxRetries = 3;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getFlushInterval() { return flushInterval; }
        public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class Correlation {
        /** Read or create a correlation ID for each incoming request and pass it on to outgoing calls. */
        private boolean enabled = true;
        private String header = "X-Correlation-Id";
        /** MDC key, so the ID also appears in normal log lines with %X{correlationId}. */
        private String mdcKey = "correlationId";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
        public String getMdcKey() { return mdcKey; }
        public void setMdcKey(String mdcKey) { this.mdcKey = mdcKey; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public boolean isThrowOnInvalid() { return throwOnInvalid; }
    public void setThrowOnInvalid(boolean throwOnInvalid) { this.throwOnInvalid = throwOnInvalid; }
    public Console getConsole() { return console; }
    public File getFile() { return file; }
    public Server getServer() { return server; }
    public Correlation getCorrelation() { return correlation; }
}
