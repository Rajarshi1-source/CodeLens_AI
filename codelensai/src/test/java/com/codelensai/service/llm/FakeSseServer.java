package com.codelensai.service.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A tiny HTTP server that answers every POST with a scripted SSE stream, so the real provider code (SDK or
 * WebClient) can be exercised without network access or API keys. The stream is written in two parts: the
 * second part waits until {@link #release()} (or a timeout), which lets a test prove that the first finding
 * reached the consumer before the response ended.
 */
final class FakeSseServer implements AutoCloseable {

    private final HttpServer server;
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    final List<Map<String, List<String>>> requestHeaders = new CopyOnWriteArrayList<>();
    private final CountDownLatch gate = new CountDownLatch(1);
    private volatile boolean gateHit;
    private final Map<String, String> state = new ConcurrentHashMap<>();

    FakeSseServer(String firstPart, String secondPart) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange, firstPart, secondPart));
        server.start();
    }

    private void handle(HttpExchange exchange, String first, String second) throws IOException {
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestHeaders.add(Map.copyOf(exchange.getRequestHeaders()));
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(first.getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                gateHit = gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.write(second.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Called by the consumer when it has seen the first finding. */
    void release() {
        gate.countDown();
    }

    /** True when the second half was released by the consumer (not by the timeout). */
    boolean releasedByConsumer() {
        return gateHit;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String sse(String event, String json) {
        return (event == null ? "" : "event: " + event + "\n") + "data: " + json + "\n\n";
    }
}
