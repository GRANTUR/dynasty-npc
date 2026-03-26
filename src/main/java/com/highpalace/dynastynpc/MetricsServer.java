package com.highpalace.dynastynpc;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Embedded HTTP server that exposes Prometheus metrics on /metrics.
 * Uses JDK's built-in com.sun.net.httpserver — zero dependencies.
 */
public class MetricsServer {

    private final HttpServer server;
    private final Logger logger;

    public MetricsServer(int port, MetricsCollector collector, Logger logger) throws IOException {
        this.logger = logger;
        this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/metrics", exchange -> {
            String metrics = collector.renderMetrics();
            byte[] response = metrics.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // Health check endpoint
        server.createContext("/health", exchange -> {
            byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.setExecutor(null); // Use default executor
    }

    public void start() {
        server.start();
        logger.info("Metrics server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        logger.info("Metrics server stopped.");
    }
}
