// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
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
        server.createContext(config.path(), this::handleMetricsPath); // main metrics endpoint
        server.start();

        logger.log(
                INFO,
                "OpenMetrics HTTP server started. hostname={0}, port={1,number,#}, path={2}",
                config.hostname(),
                config.port(),
                config.path());
    }

    @Override
    public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    private void handleMetricsPath(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGetRequest(exchange);
            } else if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleHeadRequest(exchange);
            } else {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
            }
        } catch (RuntimeException e) {
            logger.log(WARNING, "Unexpected error while handling metrics request", e);
            // Best-effort: Only attempt to send 500 if we haven't committed response yet
            try {
                if (exchange.getResponseCode() == -1) {
                    exchange.sendResponseHeaders(500, -1);
                }
            } catch (IOException ignored) {
            }
        } finally {
            exchange.close();
        }
    }

    private void handleHeadRequest(HttpExchange exchange) throws IOException {
        if (snapshotSupplier == null) {
            handleNoSnapshotSupplier(exchange);
        } else {
            setCommonOkResponseHeaders(exchange.getResponseHeaders());
            handleGzipHeaders(exchange);
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private void handleGetRequest(HttpExchange exchange) throws IOException {
        final Supplier<MetricRegistrySnapshot> snapshotSupplierRef = this.snapshotSupplier;

        if (snapshotSupplierRef == null) {
            handleNoSnapshotSupplier(exchange);
            return;
        }

        if (!isHandlingRequest.compareAndSet(false, true)) {
            logger.log(WARNING, "Another request is being processed, rejecting this one");
            exchange.getResponseHeaders().set("Retry-After", "3"); // Suggest retry after 3 seconds
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(429, -1); // Too Many Requests
            return;
        }

        try {
            MetricRegistrySnapshot registrySnapshot = snapshotSupplierRef.get();

            setCommonOkResponseHeaders(exchange.getResponseHeaders());
            boolean useGzip = handleGzipHeaders(exchange);

            exchange.sendResponseHeaders(200, 0);

            // Choose output stream based on compression and buffer size and send body
            OutputStream outputStream = exchange.getResponseBody();
            if (useGzip) {
                outputStream = new GZIPOutputStream(outputStream);
            }
            if (bufferSize != 0) {
                outputStream = new BufferedOutputStream(outputStream, bufferSize);
            }
            try (OutputStream os = outputStream) {
                writer.write(registrySnapshot, os);
            }
        } finally {
            isHandlingRequest.set(false);
        }
    }

    private void handleNoSnapshotSupplier(HttpExchange exchange) throws IOException {
        logger.log(INFO, "No snapshot supplier configured yet. method={0}", exchange.getRequestMethod());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(204, -1); // No Content
    }

    private void setCommonOkResponseHeaders(Headers responseHeaders) {
        responseHeaders.set("Content-Type", CONTENT_TYPE);
        responseHeaders.set("Cache-Control", "no-store");
        responseHeaders.set("Vary", "Accept-Encoding");
    }

    private boolean handleGzipHeaders(HttpExchange exchange) {
        List<String> encodingHeaders = exchange.getRequestHeaders().get("Accept-Encoding");
        if (encodingHeaders == null) {
            return false;
        }
        for (String encodingHeader : encodingHeaders) {
            String[] encodings = encodingHeader.split(",");
            for (String encoding : encodings) {
                if (encoding.trim().equalsIgnoreCase("gzip")) {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        logger.log(INFO, "Stopping OpenMetrics HttpServer...");
        server.stop(1);
        executorService.shutdownNow();
    }
}
