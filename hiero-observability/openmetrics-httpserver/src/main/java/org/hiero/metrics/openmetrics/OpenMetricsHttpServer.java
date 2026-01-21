// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpServerConfig;

/**
 * An HTTP server that exposes metrics in the OpenMetrics text format.
 * <p>
 * The server listens on a configurable port and path, and serves metrics snapshots
 * in response to HTTP GET requests. It supports gzip compression if the client
 * indicates support for it via the "Accept-Encoding" header.
 * <p>
 * Exposed endpoint allows only one request at a time using virtual threads per reqeust.
 * Concurrent requests will be rejected with 429 (Too Many Requests).
 */
public class OpenMetricsHttpServer implements MetricsExporter {

    private static final System.Logger logger = System.getLogger(OpenMetricsHttpServer.class.getName());

    public static final String CONTENT_TYPE = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private final OpenMetricsWriter writer;

    private final HttpServer server;
    // we don't need synchronization on this stream as only one request is handled at a time with lock
    private final UnsynchronizedByteArrayOutputStream responseBuffer = new UnsynchronizedByteArrayOutputStream(1024);
    private final ReentrantLock lock = new ReentrantLock();

    private volatile Supplier<MetricRegistrySnapshot> snapshotSupplier;

    public OpenMetricsHttpServer(@NonNull OpenMetricsHttpServerConfig config) throws IOException {
        Objects.requireNonNull(config, "OpenMetrics HTTP endpoint config must not be null");

        writer = new OpenMetricsWriter(config.decimalFormat());

        server = HttpServerProvider.provider().createHttpServer(new InetSocketAddress(config.port()), config.backlog());
        server.createContext(config.path(), this::handleSnapshots);
        // Use virtual threads to handle each request, but reject concurrent requests using the lock
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        logger.log(
                System.Logger.Level.INFO,
                "OpenMetrics HTTP endpoint started. port={}, path={}",
                config.port(),
                config.path());
    }

    @Override
    public void setSnapshotSupplier(@NonNull Supplier<MetricRegistrySnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    private void handleSnapshots(HttpExchange exchange) throws IOException {
        if (!lock.tryLock()) {
            logger.log(System.Logger.Level.WARNING, "Another request is being processed, rejecting this one");
            exchange.getResponseHeaders().set("Retry-After", "3"); // Suggest retry after 3 seconds
            exchange.sendResponseHeaders(429, 0); // Too Many Requests
            exchange.close();
            return;
        }

        try {
            final Supplier<MetricRegistrySnapshot> supplier = this.snapshotSupplier;
            if (supplier == null) {
                logger.log(System.Logger.Level.INFO, "No snapshot supplier configured yet, while handling request");
                exchange.sendResponseHeaders(204, 0); // No Content
                return;
            }

            responseBuffer.reset();
            writer.write(supplier.get(), responseBuffer);

            int contentLength = responseBuffer.size();
            logger.log(System.Logger.Level.DEBUG, "Exporting metrics snapshot, sizeBytes={}", contentLength);

            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");

            if (shouldUseCompression(exchange)) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, 0);
                try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(exchange.getResponseBody())) {
                    responseBuffer.writeTo(gzipOutputStream);
                }
            } else {
                exchange.sendResponseHeaders(200, contentLength);
                responseBuffer.writeTo(exchange.getResponseBody());
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.ERROR, "Error exporting metrics snapshots", e);
            exchange.sendResponseHeaders(500, 0);
        } finally {
            exchange.close();
            lock.unlock();
        }
    }

    private boolean shouldUseCompression(HttpExchange exchange) {
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
        server.stop(0);
    }
}
