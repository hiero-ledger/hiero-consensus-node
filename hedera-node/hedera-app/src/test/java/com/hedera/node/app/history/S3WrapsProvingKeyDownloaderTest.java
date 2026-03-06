// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class S3WrapsProvingKeyDownloaderTest {

    private static final byte[] FILE_CONTENT = "test proving key bytes".getBytes();

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private S3WrapsProvingKeyDownloader subject;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        subject = new S3WrapsProvingKeyDownloader();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void downloadsFileSuccessfully() throws Exception {
        server.createContext("/my-bucket/path/to/key.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, FILE_CONTENT.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(FILE_CONTENT);
            }
        });
        final var target = tempDir.resolve("downloaded.tar.gz");

        subject.download(baseUrl + "/my-bucket/path/to/key.tar.gz", target);

        assertArrayEquals(FILE_CONTENT, Files.readAllBytes(target));
    }

    @Test
    void throwsOnS3NotFound() {
        server.createContext("/my-bucket/missing.key", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        final var target = tempDir.resolve("missing.tar.gz");

        final var ex =
                assertThrows(IOException.class, () -> subject.download(baseUrl + "/my-bucket/missing.key", target));
        assertEquals("Object not found in S3: missing.key", ex.getMessage());
    }

    @Test
    void throwsOnInvalidUrlMissingKey() {
        final var target = tempDir.resolve("out.tar.gz");

        assertThrows(IOException.class, () -> subject.download(baseUrl + "/bucketonly", target));
    }

    @Test
    void throwsOnInvalidUrlMissingPath() {
        final var target = tempDir.resolve("out.tar.gz");

        assertThrows(IOException.class, () -> subject.download(baseUrl + "/", target));
    }
}
