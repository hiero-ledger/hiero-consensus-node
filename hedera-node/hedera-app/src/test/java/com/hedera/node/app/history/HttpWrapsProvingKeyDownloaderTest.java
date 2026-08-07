// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpWrapsProvingKeyDownloaderTest {

    private static final byte[] FILE_CONTENT = "test proving key bytes".getBytes();

    @TempDir
    private Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private HttpWrapsProvingKeyDownloader subject;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
        subject = downloaderWith(Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** A downloader with a fixed connect timeout and the given response-headers and stall timeouts. */
    private static HttpWrapsProvingKeyDownloader downloaderWith(
            final Duration responseHeadersTimeout, final Duration stallTimeout) {
        return new HttpWrapsProvingKeyDownloader(Duration.ofSeconds(30), responseHeadersTimeout, stallTimeout);
    }

    @Test
    void downloadsFileSuccessfully() throws Exception {
        server.createContext("/path/to/key.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, FILE_CONTENT.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(FILE_CONTENT);
            }
        });
        final var target = tempDir.resolve("downloaded.tar.gz");

        subject.download(baseUrl + "/path/to/key.tar.gz", target);

        assertArrayEquals(FILE_CONTENT, Files.readAllBytes(target));
    }

    @Test
    void throwsOnNotFound() {
        server.createContext("/missing.key", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        final var target = tempDir.resolve("missing.tar.gz");

        final var ex = assertThrows(IOException.class, () -> subject.download(baseUrl + "/missing.key", target));
        assertEquals("File not found at URL: " + baseUrl + "/missing.key", ex.getMessage());
        assertFalse(Files.exists(target), "the empty error-response file should be cleaned up");
    }

    @Test
    void throwsOnServerError() {
        server.createContext("/error", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        final var target = tempDir.resolve("error.tar.gz");

        final var ex = assertThrows(IOException.class, () -> subject.download(baseUrl + "/error", target));
        assertEquals("Failed to download from " + baseUrl + "/error (HTTP status 500)", ex.getMessage());
        assertFalse(Files.exists(target), "the empty error-response file should be cleaned up");
    }

    @Test
    void throwsWhenServerNeverSendsHeaders() throws Exception {
        final var release = new CountDownLatch(1);
        server.createContext("/silent", exchange -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final var target = tempDir.resolve("silent.tar.gz");

        try {
            // The stall window is long, so only the headers timeout can end this
            final var ex =
                    assertThrows(IOException.class, () -> downloaderWith(Duration.ofMillis(500), Duration.ofSeconds(30))
                            .download(baseUrl + "/silent", target));
            // The URL belongs on the exception we raise; the JDK's own timeout carries neither it nor our frames
            assertTrue(ex.getMessage().contains(baseUrl + "/silent"), ex.getMessage());
            assertInstanceOf(HttpTimeoutException.class, ex.getCause());
            // The body never started, so no file was created
            assertFalse(Files.exists(target));
        } finally {
            release.countDown();
        }
    }

    @Test
    void throwsWhenBodyTransferStalls() throws Exception {
        final var release = new CountDownLatch(1);
        server.createContext("/stalls", exchange -> {
            // Headers and a first byte go out promptly, then the rest of the body never arrives. Neither
            // connectTimeout nor the headers timeout bounds this, since both are done once headers are in.
            exchange.sendResponseHeaders(200, FILE_CONTENT.length);
            try (final var os = exchange.getResponseBody()) {
                os.write(FILE_CONTENT, 0, 1);
                os.flush();
                release.await(30, TimeUnit.SECONDS);
                os.write(FILE_CONTENT, 1, FILE_CONTENT.length - 1);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final var target = tempDir.resolve("stalled.tar.gz");

        try {
            // Generous headers timeout so it cannot fire (the body starts promptly); the short stall window is what
            // ends this. A TimeoutException cause - not HttpTimeoutException - is the witness that the body started
            // and then stalled, rather than the request never being answered.
            final var ex =
                    assertThrows(IOException.class, () -> downloaderWith(Duration.ofSeconds(10), Duration.ofSeconds(1))
                            .download(baseUrl + "/stalls", target));
            assertTrue(ex.getMessage().contains("stalled for more than"), ex.getMessage());
            assertInstanceOf(TimeoutException.class, ex.getCause());
            // The unusable partial download is cleaned up rather than left holding disk
            assertFalse(Files.exists(target));
        } finally {
            release.countDown();
        }
    }

    @Test
    void allowsATransferThatIsSlowButStillProgressing() throws Exception {
        // Two copies dribbled a byte at a time, so the transfer runs to ~2.6x the stall window while each gap stays
        // far inside it. This is the case an overall deadline of the same size would have killed, and it is why the
        // bound measures progress rather than elapsed time.
        final var slowContent = new byte[FILE_CONTENT.length * 2];
        System.arraycopy(FILE_CONTENT, 0, slowContent, 0, FILE_CONTENT.length);
        System.arraycopy(FILE_CONTENT, 0, slowContent, FILE_CONTENT.length, FILE_CONTENT.length);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, slowContent.length);
            try (final var os = exchange.getResponseBody()) {
                for (final byte b : slowContent) {
                    os.write(b);
                    os.flush();
                    Thread.sleep(60);
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        final var target = tempDir.resolve("slow.tar.gz");

        downloaderWith(Duration.ofSeconds(3), Duration.ofSeconds(1)).download(baseUrl + "/slow", target);

        assertArrayEquals(slowContent, Files.readAllBytes(target));
    }

    @Test
    void deletesPartialFileWhenTheServerFailsMidBody() throws Exception {
        server.createContext("/truncated", exchange -> {
            // Promise more than we deliver, then close early so the client sees a premature end of the body
            exchange.sendResponseHeaders(200, FILE_CONTENT.length + 4096);
            try (final var os = exchange.getResponseBody()) {
                os.write(FILE_CONTENT);
                os.flush();
            }
        });
        final var target = tempDir.resolve("truncated.tar.gz");

        assertThrows(IOException.class, () -> downloaderWith(Duration.ofSeconds(10), Duration.ofSeconds(10))
                .download(baseUrl + "/truncated", target));
        assertFalse(Files.exists(target), "a partially written body should be cleaned up on failure");
    }

    @Test
    void emptyBodyItemsDoNotCountAsProgress() {
        // HTTP/2 delivers zero-length DATA frames through onNext, so a keepalive of those must not keep the stall
        // window open. The test server here only speaks HTTP/1.1, so this drives the subscriber directly.
        final var recorded = new ArrayList<Integer>();
        final var stamp = new AtomicLong(Long.MIN_VALUE);
        final var subscriber =
                new HttpWrapsProvingKeyDownloader.ProgressStamping<>(new RecordingBodySubscriber(recorded), stamp);

        subscriber.onSubscribe(mock(Subscription.class));
        assertNotEquals(Long.MIN_VALUE, stamp.get(), "the stall clock should start when the body does");

        final long sentinel = 123L; // distinct from any real System.nanoTime()
        stamp.set(sentinel);
        subscriber.onNext(List.of(ByteBuffer.allocate(0)));
        assertEquals(sentinel, stamp.get(), "an empty item must not count as progress");

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {7, 8, 9})));
        assertNotEquals(sentinel, stamp.get(), "an item carrying bytes must count as progress");

        // Everything still reached the delegate, empty item included
        assertEquals(List.of(0, 3), recorded);
    }

    /** Minimal delegate that records how many bytes each delivered item carried. */
    private record RecordingBodySubscriber(List<Integer> recorded) implements HttpResponse.BodySubscriber<Void> {
        @Override
        public void onSubscribe(final Subscription subscription) {
            // no demand management needed for a directly driven subscriber
        }

        @Override
        public void onNext(final List<ByteBuffer> item) {
            recorded.add(item.stream().mapToInt(ByteBuffer::remaining).sum());
        }

        @Override
        public void onError(final Throwable throwable) {
            // unused
        }

        @Override
        public void onComplete() {
            // unused
        }

        @Override
        public CompletionStage<Void> getBody() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
