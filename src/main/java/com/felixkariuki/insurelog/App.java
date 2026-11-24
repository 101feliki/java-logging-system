package com.felixkariuki.insurelog;

import com.felixkariuki.insurelog.core.InsuranceLogger;
import com.felixkariuki.insurelog.core.LogStore;
import com.felixkariuki.insurelog.http.ApiServer;
import com.felixkariuki.insurelog.metrics.InsuranceMetrics;
import com.felixkariuki.insurelog.sim.TrafficSimulator;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line entry point.
 *
 * <pre>
 * java -jar insurance-logging-system-1.0.0.jar [serve] [--port 8080] [--log-dir logs] [--retention-days 90]
 * java -jar insurance-logging-system-1.0.0.jar simulate [--url http://localhost:8080] [--rate 5] [--seconds 0]
 * </pre>
 * Settings can also come from environment variables: PORT, LOG_DIR, RETENTION_DAYS,
 * MEMORY_CAPACITY and INSURELOG_API_KEY.
 */
public final class App {

    static final String VERSION = "1.0.0";

    private App() {
    }

    public static void main(String[] args) throws Exception {
        String first = args.length > 0 ? args[0] : "serve";
        String command = switch (first) {
            case "--help", "-h", "--version" -> first;
            default -> first.startsWith("--") ? "serve" : first;
        };
        Map<String, String> opts = options(args);

        switch (command) {
            case "serve" -> serve(opts);
            case "simulate" -> new TrafficSimulator(
                    opt(opts, "url", "INSURELOG_URL", "http://localhost:8080"),
                    opt(opts, "api-key", "INSURELOG_API_KEY", null))
                    .run(Integer.parseInt(opt(opts, "rate", null, "5")), Integer.parseInt(opt(opts, "seconds", null, "0")));
            case "help", "--help", "-h" -> printHelp();
            case "version", "--version" -> System.out.println("Insurance Logging System " + VERSION);
            default -> {
                System.err.println("Unknown command: " + command);
                printHelp();
                System.exit(2);
            }
        }
    }

    private static void serve(Map<String, String> opts) throws Exception {
        int port = Integer.parseInt(opt(opts, "port", "PORT", "8080"));
        Path logDir = Path.of(opt(opts, "log-dir", "LOG_DIR", "logs"));
        int retention = Integer.parseInt(opt(opts, "retention-days", "RETENTION_DAYS", "90"));
        int capacity = Integer.parseInt(opt(opts, "memory-capacity", "MEMORY_CAPACITY", "50000"));
        String apiKey = opt(opts, "api-key", "INSURELOG_API_KEY", null);

        InsuranceLogger logger = new InsuranceLogger(new LogStore(logDir, capacity, retention), new InsuranceMetrics());
        ApiServer server = new ApiServer(logger, port, apiKey);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            try {
                logger.close();
            } catch (Exception ignored) {
                // shutting down
            }
        }));

        System.out.printf("Insurance Logging System %s running on http://localhost:%d%n", VERSION, server.port());
        System.out.printf("  Logs:    %s (kept %d days)%n", logDir.toAbsolutePath(), retention);
        System.out.printf("  Metrics: http://localhost:%d/metrics%n", server.port());
        System.out.printf("  API key: %s%n", apiKey == null ? "not required" : "required (X-API-Key header)");
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(args[i].substring(2), args[++i]);
            }
        }
        return out;
    }

    private static String opt(Map<String, String> opts, String name, String env, String fallback) {
        if (opts.containsKey(name)) {
            return opts.get(name);
        }
        if (env != null && System.getenv(env) != null && !System.getenv(env).isBlank()) {
            return System.getenv(env);
        }
        return fallback;
    }

    private static void printHelp() {
        System.out.println("""
                Insurance Logging System %s

                Usage:
                  java -jar insurance-logging-system-%s.jar [serve] [options]
                  java -jar insurance-logging-system-%s.jar simulate [options]

                serve options:
                  --port <n>             HTTP port (env PORT, default 8080)
                  --log-dir <path>       where daily log files go (env LOG_DIR, default ./logs)
                  --retention-days <n>   delete log files older than this (env RETENTION_DAYS, default 90)
                  --memory-capacity <n>  events kept in memory for search (env MEMORY_CAPACITY, default 50000)
                  --api-key <key>        require X-API-Key on /api/* (env INSURELOG_API_KEY)

                simulate options:
                  --url <url>            server to send demo events to (default http://localhost:8080)
                  --rate <n>             events per second (default 5)
                  --seconds <n>          how long to run, 0 = until stopped (default 0)
                  --api-key <key>        API key, if the server needs one
                """.formatted(VERSION, VERSION, VERSION));
    }
}
