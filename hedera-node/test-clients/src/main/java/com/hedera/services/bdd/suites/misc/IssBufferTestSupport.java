// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.updateBootstrapProperties;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.hedera.services.bdd.spec.SpecOperation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Shared helpers for the ISS x block-buffer x block-node investigation tests. Local/throwaway only — the goal is to
 * OBSERVE when the ISS-round block survives in the in-memory buffer at detection and when it is lost, not to prove the
 * current behavior is correct. See {@code .context/iss-investigation-test-plan.md}.
 */
final class IssBufferTestSupport {
    private IssBufferTestSupport() {}

    /** Starts an in-JVM S3-compatible multipart sink that records every uploaded object key into {@code receivedKeys}. */
    static HttpServer startS3Mock(final Set<String> receivedKeys) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleS3Request(exchange, receivedKeys));
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            final Thread thread = new Thread(runnable, "iss-buffer-s3-mock");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    /** True if any recorded object key contains {@code segment} and ends with {@code suffix} (e.g. "/iss/", ".iss.gz"). */
    static boolean receivedKeyMatches(final Set<String> keys, final String segment, final String suffix) {
        return keys.stream().anyMatch(key -> key.contains(segment) && key.endsWith(suffix));
    }

    /** Polls until {@link #receivedKeyMatches} holds or the timeout elapses (best-effort; for use inside verify()). */
    static void awaitKey(final Set<String> keys, final String segment, final String suffix, final Duration timeout) {
        final long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline && !receivedKeyMatches(keys, segment, suffix)) {
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Builds the reconnect-time config op for one node: writes the bucket credentials and (optionally) turns on the
     * ISS-block capture pointed at the in-JVM S3 mock, the ISS-inducing {@code ledger.transfers.maxLen=5}, and a
     * specific {@code blockStream.buffer.ackedBlocksToRetain} (the acked-retention window). Any of the three toggles
     * can be off so a second diverging node (for CATASTROPHIC_ISS) needs only {@code induceIss}.
     */
    static SpecOperation configureNode(
            final long nodeId,
            final int s3Port,
            final boolean induceIss,
            final Integer ackedBlocksToRetain,
            final boolean enableUpload) {
        return doingContextual(spec -> {
            final var node = spec.getNetworkNodes().get((int) nodeId);
            final var props = node.getExternalPath(APPLICATION_PROPERTIES);
            final var configDir = node.getExternalPath(DATA_CONFIG_DIR);
            try {
                Files.writeString(
                        configDir.resolve("iss-bucket-credentials.properties"), "accessKey=test\nsecretKey=test\n");
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
            final Map<String, String> overrides = new LinkedHashMap<>();
            if (induceIss) {
                // node's limit is below the transfer's adjustment count, so it rejects a txn the others apply.
                overrides.put("ledger.transfers.maxLen", "5");
            }
            if (enableUpload) {
                overrides.put("failureBlockUpload.issBlockUploadEnabled", "true");
                overrides.put("failureBlockUpload.endpoint", "http://127.0.0.1:" + s3Port + "/");
                overrides.put("failureBlockUpload.bucketName", "iss-debug");
                overrides.put("failureBlockUpload.region", "us-east-1");
                overrides.put(
                        "failureBlockUpload.credentialsFileDir",
                        configDir.toAbsolutePath().toString());
                overrides.put("failureBlockUpload.maxRetries", "0");
            }
            if (ackedBlocksToRetain != null) {
                // The acked-retention window: how many acknowledged blocks the buffer keeps (default 10).
                overrides.put("blockStream.buffer.ackedBlocksToRetain", String.valueOf(ackedBlocksToRetain));
            }
            updateBootstrapProperties(props, overrides);
        });
    }

    /** Minimal S3 multipart-upload mock: records each uploaded part's object key (path) and 200s the protocol. */
    private static void handleS3Request(final HttpExchange exchange, final Set<String> receivedKeys)
            throws IOException {
        final String method = exchange.getRequestMethod();
        final String query = exchange.getRequestURI().getRawQuery();
        final String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes(); // drain
        if ("POST".equals(method) && query != null && query.contains("uploads")) {
            respond(
                    exchange,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                            + "<InitiateMultipartUploadResult><UploadId>mock-upload-id</UploadId>"
                            + "</InitiateMultipartUploadResult>");
        } else if ("PUT".equals(method) && query != null && query.contains("partNumber")) {
            receivedKeys.add(path);
            exchange.getResponseHeaders().set("ETag", "\"mock-etag\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        } else if ("POST".equals(method) && query != null && query.contains("uploadId")) {
            respond(exchange, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult/>");
        } else {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        }
    }

    private static void respond(final HttpExchange exchange, final String xml) throws IOException {
        final byte[] body = xml.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        try (final OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
