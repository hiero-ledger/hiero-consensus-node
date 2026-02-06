// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpServerConfig;

/**
 * An HTTP server that exposes metrics in the OpenMetrics text format.
 * <p>
 * The server listens on a configurable hostname, port and path, and serves metrics snapshots
 * in response to HTTP GET requests. HEAD requests are also supported for health checks.
 * It supports gzip compression if the client indicates support for it via the "Accept-Encoding" header.
 * <p>
 * Exposed endpoint allows only one GET request at a time using virtual threads per request.
 * Concurrent GET requests will be rejected with 429 (Too Many Requests).
 */
class OpenMetricsHttpServer implements MetricsExporter {

    private static final System.Logger logger = System.getLogger(OpenMetricsHttpServer.class.getName());

    public static final String CONTENT_TYPE = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private final OpenMetricsWriter writer;
    private final ExecutorService executorService;

    private final HttpServer server;
    private final int bufferSize;

    // This flag is used to prohibit concurrent GET requests to scrape metrics.
    // Additionally, OpenMetricsWriter is not thread safe due to DecimalFormat usage.
    private final AtomicBoolean isHandlingRequest = new AtomicBoolean(false);

    private volatile Supplier<MetricRegistrySnapshot> snapshotSupplier;

    public OpenMetricsHttpServer(@NonNull OpenMetricsHttpServerConfig config) throws IOException {
        Objects.requireNonNull(config, "OpenMetrics HTTP endpoint config must not be null");

        bufferSize = config.bufferSize();
        writer = new OpenMetricsWriter(config.decimalFormat());
        // Use virtual threads to handle each request, but reject concurrent GET requests using the boolean flag
        executorService = Executors.newVirtualThreadPerTaskExecutor();

        final InetSocketAddress address;
        if (config.hostname() != null && !config.hostname().isBlank()) {
            address = new InetSocketAddress(config.hostname(), config.port());
        } else {
            address = new InetSocketAddress(config.port());
        }

        // Use a small accept backlog to absorb short TCP connection bursts so clients can receive an HTTP
        // response (e.g., 429) instead of failing at the TCP layer. GET concurrency is limited separately.
        server = HttpServerProvider.provider().createHttpServer(address, 3);
        server.setExecutor(executorService);
        server.createContext(config.path(), this::handleSnapshots); // main metrics endpoint
        server.start();

        logger.log(
                System.Logger.Level.INFO,
                "OpenMetrics HTTP server started. hostname={}, port={}, path={}",
                config.hostname(),
                config.port(),
                config.path());
    }

    @Override
    public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    private void handleSnapshots(HttpExchange exchange) throws IOException {
        // use local variable to track if we set the flag for handling GET request, but not HEAD
        boolean handlingRequest = false;
        try {
            // allow only GET and HEAD methods
            final String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            // No Content if no snapshot supplier is configured yet
            final Supplier<MetricRegistrySnapshot> supplier = this.snapshotSupplier;
            if (supplier == null) {
                logger.log(System.Logger.Level.INFO, "No snapshot supplier configured yet, while handling request");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(204, -1); // No Content
                return;
            }

            // allow only one GET request at a time
            if ("GET".equals(method)) {
                handlingRequest = isHandlingRequest.compareAndSet(false, true);
                if (!handlingRequest) {
                    logger.log(System.Logger.Level.WARNING, "Another request is being processed, rejecting this one");
                    exchange.getResponseHeaders().set("Retry-After", "3"); // Suggest retry after 3 seconds
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(429, -1); // Too Many Requests
                    return;
                }
            }

            // Set common response headers for GET and HEAD requests
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Vary", "Accept-Encoding");

            final boolean gzip = shouldGzip(exchange);
            if (gzip) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }

            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            MetricRegistrySnapshot registrySnapshot = supplier.get();
            exchange.sendResponseHeaders(200, 0);

            // Choose output stream based on compression and buffer size and send body
            OutputStream outputStream = exchange.getResponseBody();
            if (gzip) {
                outputStream = new GZIPOutputStream(outputStream);
            }
            if (bufferSize != 0) {
                outputStream = new BufferedOutputStream(outputStream, bufferSize);
            }
            try (OutputStream os = outputStream) {
                writer.write(registrySnapshot, os);
            }
        } catch (RuntimeException e) {
            logger.log(System.Logger.Level.WARNING, "Unexpected error during exporting metrics snapshots", e);
            // Best-effort: Only attempt to send 500 if we haven't committed response yet
            try {
                if (exchange.getResponseCode() == -1) {
                    exchange.sendResponseHeaders(500, -1);
                }
            } catch (IOException ignored) {
            }
        } finally {
            // reset the flag which could be set only by GET requests
            if (handlingRequest) {
                isHandlingRequest.set(false);
            }
            exchange.close();
        }
    }

    private boolean shouldGzip(HttpExchange exchange) {
        List<String> encodingHeaders = exchange.getRequestHeaders().get("Accept-Encoding");
        if (encodingHeaders == null) {
            return false;
        }
        for (String encodingHeader : encodingHeaders) {
            String[] encodings = encodingHeader.split(",");
            for (String encoding : encodings) {
                if (encoding.trim().equalsIgnoreCase("gzip")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        server.stop(1);
        executorService.shutdownNow();
    }
}
