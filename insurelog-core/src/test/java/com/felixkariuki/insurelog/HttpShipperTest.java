package com.felixkariuki.insurelog;

import com.fasterxml.jackson.databind.JsonNode;
import com.felixkariuki.insurelog.core.HttpShipper;
import com.felixkariuki.insurelog.core.Json;
import com.felixkariuki.insurelog.model.Category;
import com.felixkariuki.insurelog.model.LogEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpShipperTest {

    @Test
    void sendsBatchesWithApiKeyAndFlushesOnClose() throws Exception {
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        AtomicReference<String> key = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/logs", ex -> {
            key.set(ex.getRequestHeaders().getFirst("X-API-Key"));
            JsonNode body = Json.MAPPER.readTree(ex.getRequestBody());
            batchSizes.add(body.size());
            ex.sendResponseHeaders(201, -1);
            ex.close();
        });
        server.start();
        try {
            HttpShipper shipper = new HttpShipper("http://localhost:" + server.getAddress().getPort(), "k1",
                    10, Duration.ofMillis(200), 1000, 1);
            for (int i = 0; i < 25; i++) {
                shipper.accept(LogEvent.builder(Category.PAYMENT, "PREMIUM_PAID").message("p" + i).build());
            }
            shipper.close();
            assertEquals(25, batchSizes.stream().mapToInt(Integer::intValue).sum());
            assertEquals(25, shipper.sentCount());
            assertEquals("k1", key.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dropsInsteadOfBlockingWhenServerIsStuck() throws Exception {
        // a server that never answers keeps the shipper busy, so the small queue fills up
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/logs", ex -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                // test finished
            }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            HttpShipper shipper = new HttpShipper("http://localhost:" + server.getAddress().getPort(), null,
                    1, Duration.ofMillis(10), 5, 0);
            shipper.accept(LogEvent.builder(Category.SYSTEM, "HEARTBEAT").message("first").build());
            Thread.sleep(300); // let the worker pick it up and get stuck sending
            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                shipper.accept(LogEvent.builder(Category.SYSTEM, "HEARTBEAT").message("x").build());
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(true, elapsedMs < 1000, "accept() must not block");
            assertEquals(5, shipper.queueSize());
            assertEquals(45, shipper.droppedCount());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsEventsDuringAnOutageAndDeliversThemAfterwards() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/logs", ex -> {
            JsonNode body = Json.MAPPER.readTree(ex.getRequestBody());
            if (calls.incrementAndGet() <= 2) {
                ex.sendResponseHeaders(503, -1); // server "down" for the first two attempts
            } else {
                received.addAndGet(body.size());
                ex.sendResponseHeaders(201, -1);
            }
            ex.close();
        });
        server.start();
        try {
            HttpShipper shipper = new HttpShipper("http://localhost:" + server.getAddress().getPort(), null,
                    100, Duration.ofMillis(50), 1000, 0);
            for (int i = 0; i < 20; i++) {
                shipper.accept(LogEvent.builder(Category.CLAIM, "CLAIM_SUBMITTED").message("c" + i).build());
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (shipper.sentCount() < 20 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(20, shipper.sentCount());
            assertEquals(20, received.get());
            assertEquals(0, shipper.droppedCount());
            assertEquals(2, shipper.failedBatchCount());
            shipper.close();
        } finally {
            server.stop(0);
        }
    }
}
