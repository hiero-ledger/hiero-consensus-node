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
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.metrics.api.export.extension.PullingMetricsExporterAdapter;
import org.hiero.metrics.api.export.extension.writer.OpenMetricsSnapshotsWriter;
import org.hiero.metrics.api.export.extension.writer.UnsynchronizedByteArrayOutputStream;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpEndpointConfig;

/**
 * An HTTP server that exposes metrics in the OpenMetrics format.
 * <p>
 * The server listens on a configurable port and path, and serves metrics snapshots
 * in response to HTTP GET requests. It supports gzip compression if the client
 * indicates support for it via the "Accept-Encoding" header.
 * <p>
 * This class extends {@link PullingMetricsExporterAdapter} to periodically pull
 * metrics snapshots for export.
 */
public class OpenMetricsHttpEndpoint extends PullingMetricsExporterAdapter {

    private static final Logger logger = LogManager.getLogger(OpenMetricsHttpEndpoint.class);

    public static final String CONTENT_TYPE = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private final HttpServer server;
    private final UnsynchronizedByteArrayOutputStream responseBuffer = new UnsynchronizedByteArrayOutputStream(1024);
    private final ReentrantLock lock = new ReentrantLock();

    private final OpenMetricsSnapshotsWriter writer = OpenMetricsSnapshotsWriter.DEFAULT;

    public OpenMetricsHttpEndpoint(@NonNull OpenMetricsHttpEndpointConfig config) throws IOException {
        super("open-metrics-http-endpoint");
        Objects.requireNonNull(config, "OpenMetrics HTTP endpoint config must not be null");

        final HttpServerProvider provider = HttpServerProvider.provider();
        server = provider.createHttpServer(new InetSocketAddress(config.port()), config.backlog());
        server.createContext(config.path(), this::handleSnapshots);
        server.setExecutor(null);
        server.start();

        logger.info("OpenMetrics HTTP endpoint started. port={}, path={}", config.port(), config.path());
    }

    private void handleSnapshots(HttpExchange exchange) throws IOException {
        if (!lock.tryLock()) {
            logger.warn("Another request is being processed, rejecting this one");
            exchange.sendResponseHeaders(503, 0); // Service Unavailable
            exchange.close();
            return;
        }

        try {
            Optional<MetricsSnapshot> optionalSnapshot = getSnapshot();
            if (optionalSnapshot.isEmpty()) {
                exchange.sendResponseHeaders(204, 0); // No Content
                return;
            }

            responseBuffer.reset();
            writer.write(optionalSnapshot.get(), responseBuffer);

            int contentLength = responseBuffer.size();
            logger.debug("Exporting metrics snapshot, sizeBytes={}", contentLength);

            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            boolean useCompression = shouldUseCompression(exchange);
            if (useCompression) {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            }

            if (useCompression) {
                exchange.sendResponseHeaders(200, 0);
                try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(exchange.getResponseBody())) {
                    responseBuffer.writeTo(gzipOutputStream);
                }
            } else {
                exchange.sendResponseHeaders(200, contentLength);
                responseBuffer.writeTo(exchange.getResponseBody());
            }
        } catch (RuntimeException e) {
            //  TODO additional error handling ?
            logger.error("Error exporting metrics snapshot", e);
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
    public void close() throws IOException {
        super.close();
        server.stop(0);
    }
}
