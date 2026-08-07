// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.config.data.FailureBlockUploadConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test that runs the <b>real</b> {@link BuckyBlockUploader} (and its real {@code com.hedera.bucky.S3Client})
 * against an in-JVM S3 mock built on {@link HttpServer}. Unlike {@link BuckyBlockUploaderTest} (which stubs the S3
 * client), this exercises the actual multipart HTTP path bucky issues — create ({@code ?uploads}), upload-part
 * ({@code ?partNumber&uploadId}), complete ({@code ?uploadId}) — and asserts the bytes, object keys, and content types
 * that land "in the bucket". It needs no Docker and no extra dependencies (signing is performed by bucky but not
 * cryptographically verified here; bucky's own suite covers signature fidelity against real MinIO).
 */
class BuckyBlockUploaderS3MockTest {

    private static final String BUCKET = "test-bucket";

    @TempDir
    Path tempDir;

    @Test
    void uploadsRealMultipartRequestsForContentsAndProof() throws Exception {
        final Map<String, ByteArrayOutputStream> objects = new ConcurrentHashMap<>();
        final Map<String, String> contentTypes = new ConcurrentHashMap<>();

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String method = exchange.getRequestMethod();
            final String query = exchange.getRequestURI().getRawQuery();
            final String path = exchange.getRequestURI().getPath();
            final String key =
                    path.startsWith("/" + BUCKET + "/") ? path.substring(("/" + BUCKET + "/").length()) : path;
            final byte[] body = exchange.getRequestBody().readAllBytes();

            if ("POST".equals(method) && query != null && query.contains("uploads")) {
                // createMultipartUpload — bucky parses <UploadId> from the body
                contentTypes.put(key, exchange.getRequestHeaders().getFirst("Content-Type"));
                respond(
                        exchange,
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                + "<InitiateMultipartUploadResult><UploadId>mock-upload-id</UploadId>"
                                + "</InitiateMultipartUploadResult>");
            } else if ("PUT".equals(method) && query != null && query.contains("partNumber")) {
                // multipartUploadPart — accumulate bytes; bucky reads the ETag response header
                objects.computeIfAbsent(key, k -> new ByteArrayOutputStream()).writeBytes(body);
                exchange.getResponseHeaders().set("ETag", "\"mock-etag\"");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if ("POST".equals(method) && query != null && query.contains("uploadId")) {
                // completeMultipartUpload
                respond(exchange, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CompleteMultipartUploadResult/>");
            } else {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
            }
        });
        final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        try {
            final int port = server.getAddress().getPort();

            final FailureBlockUploadConfig config = HederaTestConfigBuilder.create()
                    .withConfigDataType(FailureBlockUploadConfig.class)
                    .withValue("failureBlockUpload.issBlockUploadEnabled", "true")
                    .withValue("failureBlockUpload.bucketName", BUCKET)
                    .withValue("failureBlockUpload.endpoint", "http://127.0.0.1:" + port + "/")
                    .withValue("failureBlockUpload.region", "us-east-1")
                    .withValue("failureBlockUpload.objectKeyPrefix", "iss-blocks")
                    .withValue("failureBlockUpload.maxRetries", "0")
                    .getOrCreateConfig()
                    .getConfigData(FailureBlockUploadConfig.class);

            final Path creds = tempDir.resolve("creds.properties");
            Files.writeString(creds, "accessKey=AKIAEXAMPLE\nsecretKey=secretExample\n");

            final String base = FileBlockItemWriter.longToFileName(7L);
            final Path pnd = tempDir.resolve(base + ".pnd.gz");
            final Path proof = tempDir.resolve(base + ".pnd.json");
            final byte[] pndBytes = "pretend-gzipped-block-contents".getBytes(UTF_8);
            final byte[] proofBytes = "{\"pendingProof\":true}".getBytes(UTF_8);
            Files.write(pnd, pndBytes);
            Files.write(proof, proofBytes);

            final String incident = "2026-06-16T00-00-00Z";
            final var uploader = new BuckyBlockUploader(config, "0.0.3", creds);
            final List<String> uris = uploader.uploadBlockFiles(UploadCategory.ISS, incident, List.of(pnd));

            final String contentsKey = "iss-blocks/0.0.3/iss/" + incident + "/" + base + "/" + base + ".pnd.gz";
            final String proofKey = "iss-blocks/0.0.3/iss/" + incident + "/" + base + "/" + base + ".pnd.json";

            // Both the contents and the proof sidecar reached the "bucket", byte-for-byte, with the right content
            // types.
            assertThat(objects).containsKeys(contentsKey, proofKey);
            assertThat(objects.get(contentsKey).toByteArray()).isEqualTo(pndBytes);
            assertThat(objects.get(proofKey).toByteArray()).isEqualTo(proofBytes);
            assertThat(contentTypes.get(contentsKey)).isEqualTo("application/gzip");
            assertThat(contentTypes.get(proofKey)).isEqualTo("application/json");

            // The returned URIs point at the uploaded objects.
            final String urlBase = "http://127.0.0.1:" + port + "/" + BUCKET + "/";
            assertThat(uris).containsExactly(urlBase + contentsKey, urlBase + proofKey);
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    private static void respond(final com.sun.net.httpserver.HttpExchange exchange, final String xml)
            throws IOException {
        final byte[] bytes = xml.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, bytes.length);
        try (final OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
