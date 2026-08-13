// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Downloads the WRAPS proving key from an HTTP URL.
 */
public class HttpWrapsProvingKeyDownloader {
    private static final Logger log = LogManager.getLogger(HttpWrapsProvingKeyDownloader.class);

    /** Progress-clock value before the body starts arriving, when a stall cannot yet be diagnosed. */
    private static final long BODY_NOT_STARTED = Long.MIN_VALUE;

    private final Duration connectTimeout;
    private final Duration responseHeadersTimeout;
    private final Duration stallTimeout;

    /**
     * @param connectTimeout bound on establishing the connection
     * @param responseHeadersTimeout bound on time-to-response-headers. {@link HttpRequest.Builder#timeout(Duration)}
     *     stops applying once the headers are in, so this covers a server that accepts the connection and then
     *     never replies; the JDK re-applies it to each of its up-to-five attempts. The body after headers is bounded
     *     by {@code stallTimeout}. Keep it shorter than the stall window so a silent server is reported as the
     *     request timing out rather than racing the stall check.
     * @param stallTimeout how long the body may go without delivering a single byte before we give up. Nothing in
     *     the JDK client bounds the body once headers are in, so without this a server that answers and then stalls
     *     mid-body blocks the calling thread forever. This measures lack of progress rather than elapsed time: an
     *     overall deadline would have to encode both the archive size and the node's link speed, so any value would
     *     either cut off a slow but healthy download - permanently, since a retry restarts from zero - or be too
     *     loose to bound anything. The one gap it leaves is a server dribbling just fast enough to keep resetting
     *     the window; closing that needs a minimum-throughput floor, which is not worth having since the download
     *     URL points at our own artifact host and a floor would misfire on genuinely slow links.
     */
    public HttpWrapsProvingKeyDownloader(
            @NonNull final Duration connectTimeout,
            @NonNull final Duration responseHeadersTimeout,
            @NonNull final Duration stallTimeout) {
        this.connectTimeout = requireNonNull(connectTimeout);
        this.responseHeadersTimeout = requireNonNull(responseHeadersTimeout);
        this.stallTimeout = requireNonNull(stallTimeout);
    }

    public void download(@NonNull final String downloadUrl, @NonNull final Path targetPath) throws IOException {
        try (final var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout)
                .build()) {
            final var request = HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(responseHeadersTimeout)
                    .GET()
                    .build();
            // Sentinel until the first onSubscribe: while the body has not started, the per-attempt headers
            // timeout is the bound, not the stall window (a retry sequence can outlast the window without a byte).
            final var lastProgressNanos = new AtomicLong(BODY_NOT_STARTED);
            final var future = httpClient.sendAsync(
                    request,
                    info -> new ProgressStamping<>(fileHandler(targetPath).apply(info), lastProgressNanos));
            final var response = awaitResponse(future, httpClient, downloadUrl, stallTimeout, lastProgressNanos);
            final int statusCode = response.statusCode();
            if (statusCode == 404) {
                throw new IOException("File not found at URL: " + downloadUrl);
            } else if (statusCode != 200) {
                throw new IOException("Failed to download from " + downloadUrl + " (HTTP status " + statusCode + ")");
            }
        } catch (final IOException | RuntimeException e) {
            // Whatever reached disk is unusable, whether we gave up on the transfer, the server failed us partway,
            // or the status was not 200. Leaving it holds space until the next attempt and costs the next startup a
            // multi-gigabyte hash just to reject it.
            deleteQuietly(targetPath);
            throw e;
        }
    }

    private static HttpResponse.BodyHandler<Path> fileHandler(@NonNull final Path targetPath) {
        return HttpResponse.BodyHandlers.ofFile(
                targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * Blocks until the response completes, treating the body as stalled if a whole {@code stallTimeout} passes with
     * no bytes delivered. The calling thread supervises its own transfer by waiting in stall-sized slices, so no
     * watchdog thread is needed; a stall is therefore noticed between one and two windows after it begins, and
     * never before a full window of silence.
     */
    private static HttpResponse<Path> awaitResponse(
            @NonNull final CompletableFuture<HttpResponse<Path>> future,
            @NonNull final HttpClient httpClient,
            @NonNull final String downloadUrl,
            @NonNull final Duration stallTimeout,
            @NonNull final AtomicLong lastProgressNanos)
            throws IOException {
        // Not a retry loop: the JDK client does its own (bounded) retries internally. Each iteration waits one
        // stall window for the single in-flight request; a TimeoutException from get() just means "still going",
        // so we re-check progress and wait again. It ends when the response arrives, a full window passes with no
        // bytes (stall), or the exchange fails - so the only way to keep looping is a transfer that keeps
        // delivering bytes.
        HttpResponse<Path> response = null;
        while (response == null) {
            try {
                response = future.get(stallTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (final TimeoutException e) {
                final long lastProgress = lastProgressNanos.get();
                if (lastProgress != BODY_NOT_STARTED && System.nanoTime() - lastProgress >= stallTimeout.toNanos()) {
                    abort(future, httpClient);
                    throw new IOException("Download from " + downloadUrl + " stalled for more than " + stallTimeout, e);
                }
                // Otherwise the body has not started (the headers timeout will end it) or bytes arrived within the
                // window; keep waiting.
            } catch (final InterruptedException e) {
                abort(future, httpClient);
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted: " + downloadUrl, e);
            } catch (final ExecutionException e) {
                // Wrap rather than rethrow the cause: it was constructed on an HttpClient thread, so by itself it
                // carries no frame from here, and frequently no message either.
                throw new IOException("Failed to download from " + downloadUrl, e.getCause());
            }
        }
        return response;
    }

    /**
     * Cancels the exchange so that closing the client does not block waiting for it; {@link HttpClient#close()}
     * shuts down gracefully and would otherwise wait on the operation we just gave up on.
     */
    private static void abort(@NonNull final CompletableFuture<?> future, @NonNull final HttpClient httpClient) {
        future.cancel(true);
        httpClient.shutdownNow();
    }

    private static void deleteQuietly(@NonNull final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException e) {
            log.warn("Failed to delete unusable WRAPS proving key download at {}", path, e);
        }
    }

    /**
     * Delegating {@link BodySubscriber} that records when body bytes last arrived, so the caller can tell a slow
     * transfer from a stalled one. Demand is left entirely to the delegate, which keeps the real
     * {@link Subscription}.
     */
    static final class ProgressStamping<T> implements BodySubscriber<T> {
        private final BodySubscriber<T> delegate;
        private final AtomicLong lastProgressNanos;

        ProgressStamping(@NonNull final BodySubscriber<T> delegate, @NonNull final AtomicLong lastProgressNanos) {
            this.delegate = requireNonNull(delegate);
            this.lastProgressNanos = requireNonNull(lastProgressNanos);
        }

        @Override
        public void onSubscribe(final Subscription subscription) {
            // The body is starting; start the stall clock (until now the per-attempt headers timeout was the bound)
            lastProgressNanos.set(System.nanoTime());
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(final List<ByteBuffer> item) {
            // Read before delegating, since writing the buffers to the channel consumes them
            final boolean carriedBytes = item.stream().anyMatch(ByteBuffer::hasRemaining);
            delegate.onNext(item);
            // Empty items are not progress: HTTP/2 delivers zero-length DATA frames through onNext, and counting
            // those would let a keepalive hold the window open at zero bandwidth. Stamped after the write so the
            // window means "nothing arrived", not "nothing arrived and the last chunk was slow to persist".
            if (carriedBytes) {
                lastProgressNanos.set(System.nanoTime());
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }
    }
}
