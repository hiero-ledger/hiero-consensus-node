// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import static com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.exceptions.NetworkControlUnavailableException;

/**
 * A client for interacting with the Toxiproxy control server REST API.
 * This client allows creating and updating proxies to simulate network conditions.
 *
 * <p>Toxiproxy wraps its whole REST API in Go's {@code http.TimeoutHandler} with a 25-second limit and returns an
 * empty {@code 503} when a request does not finish in time (a toxic update can wedge past that limit when a link is
 * backpressured). Such a 503 describes a transient, self-healing condition rather than a permanent failure, so every
 * request is given a per-request timeout that sits deliberately <em>above</em> Toxiproxy's internal limit and is
 * retried with exponential backoff on {@code 5xx} responses and {@link IOException}s. A {@code 4xx} response signals a
 * fixture logic bug and fails fast without retrying.
 */
public class ToxiproxyClient {

    private static final Logger log = LogManager.getLogger();

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    /** HTTP status code returned by Toxiproxy when a proxy or toxin with the same name already exists. */
    private static final int HTTP_CONFLICT = 409;

    /** Timeout for establishing a TCP connection to the Toxiproxy control server. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10L);

    /**
     * Per-request timeout. Deliberately above Toxiproxy's internal 25-second {@code TimeoutHandler} limit so that we
     * still receive its {@code 503} response rather than racing it at the socket layer.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35L);

    /** Total number of attempts (one initial attempt plus up to five retries with backoff 1s, 2s, 4s, 8s, 16s). */
    private static final int MAX_ATTEMPTS = 6;

    /** Backoff before the first retry. Doubles on each subsequent retry up to {@link #MAX_BACKOFF}. */
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1L);

    /** Upper bound for the exponential backoff between retries. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(16L);

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final URI baseUri;

    /**
     * Constructs a new ToxiproxyClient instance.
     *
     * @param host the host on which the Toxiproxy control server is running
     * @param controlPort the port on which the Toxiproxy control server is running
     */
    public ToxiproxyClient(@NonNull final String host, final int controlPort) {
        this.baseUri = URI.create(String.format("http://%s:%d/proxies", host, controlPort));
    }

    /**
     * Creates a new proxy with the specified configuration.
     *
     * <p>If the request comes back with {@code 409 (already exists)} <em>after</em> at least one retry, an earlier
     * attempt actually succeeded on the server but its response was lost; the existing proxy is fetched and returned.
     *
     * @param proxy the proxy configuration to create
     * @return the created proxy as it is stored on the server
     */
    @NonNull
    public Proxy createProxy(@NonNull final Proxy proxy) {
        final HttpResponse<String> response = send(postRequest(baseUri, proxy), EarlierSuccess.ACCEPTED);
        if (response.statusCode() == HTTP_CONFLICT) {
            log.debug("Proxy '{}' already exists after a retry; fetching the existing proxy", proxy.name());
            return getProxyByName(proxy.name());
        }
        return readProxyFromResponse(response);
    }

    /**
     * Updates an existing proxy with the specified configuration.
     *
     * @param proxy the proxy configuration to update
     * @return the updated proxy as it is stored on the server
     */
    @NonNull
    public Proxy updateProxy(@NonNull final Proxy proxy) {
        final URI uri = new UriBuilder(baseUri).path(proxy.name()).build();
        return readProxyFromResponse(send(postRequest(uri, proxy), EarlierSuccess.NOT_ACCEPTED));
    }

    /**
     * Creates a new toxin for the specified proxy with the given configuration.
     *
     * <p>If the request comes back with {@code 409 (already exists)} <em>after</em> at least one retry, an earlier
     * attempt actually succeeded on the server but its response was lost; this is treated as success.
     *
     * @param proxy the proxy to which the toxin will be added
     * @param toxin the toxin configuration to create
     */
    public void createToxin(@NonNull final Proxy proxy, @NonNull final Toxin toxin) {
        final URI uri =
                new UriBuilder(baseUri).path(proxy.name()).path("toxics").build();
        final HttpResponse<String> response = send(postRequest(uri, toxin), EarlierSuccess.ACCEPTED);
        if (response.statusCode() == HTTP_CONFLICT) {
            log.debug(
                    "Toxin '{}' on proxy '{}' already exists after a retry; treating as success",
                    toxin.name(),
                    proxy.name());
        }
    }

    /**
     * Updates an existing toxin for the specified proxy with the given configuration.
     *
     * @param proxy the proxy to which the toxin belongs
     * @param toxin the toxin configuration to update
     */
    public void updateToxin(@NonNull final Proxy proxy, @NonNull final Toxin toxin) {
        final URI uri = new UriBuilder(baseUri)
                .path(proxy.name())
                .path("toxics")
                .path(toxin.name())
                .build();
        send(postRequest(uri, toxin), EarlierSuccess.NOT_ACCEPTED);
    }

    /**
     * Fetches a proxy by its name from the Toxiproxy control server.
     *
     * @param name the name of the proxy to fetch
     * @return the proxy as it is stored on the server
     */
    @NonNull
    private Proxy getProxyByName(@NonNull final String name) {
        final URI uri = new UriBuilder(baseUri).path(name).build();
        final HttpRequest request =
                HttpRequest.newBuilder().uri(uri).timeout(REQUEST_TIMEOUT).GET().build();
        return readProxyFromResponse(send(request, EarlierSuccess.NOT_ACCEPTED));
    }

    /**
     * Builds a JSON {@code POST} request for the given URI and payload.
     *
     * @param uri the target URI
     * @param payload the payload to serialize as the request body
     * @return the constructed request
     */
    @NonNull
    private static HttpRequest postRequest(@NonNull final URI uri, @NonNull final Object payload) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                .build();
    }

    /**
     * Sends a request, retrying on {@code 5xx} responses and {@link IOException}s with exponential backoff, and returns
     * the successful ({@code 2xx}) response. A {@code 4xx} response signals a fixture logic bug and fails fast without
     * retrying.
     *
     * <p>When {@code earlierSuccess} is {@link EarlierSuccess#ACCEPTED} and a retried request comes back reporting the
     * resource already exists ({@code 409}), an earlier attempt succeeded on the server but its response was lost; that
     * response is returned so the caller can treat it as an idempotent success. A {@code 409} on the very first attempt
     * (nothing was retried) still fails, because then the resource genuinely pre-existed.
     *
     * @param request the request to send
     * @param earlierSuccess whether a repeat reporting the work was already done should be accepted as success
     * @return the successful ({@code 2xx}), or accepted already-done, response
     * @throws AssertionError if the server returns an unaccepted {@code 4xx} response
     * @throws NetworkControlUnavailableException if the request keeps failing after {@link #MAX_ATTEMPTS} attempts
     */
    @NonNull
    private HttpResponse<String> send(
            @NonNull final HttpRequest request, @NonNull final EarlierSuccess earlierSuccess) {
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                final int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response;
                }
                if (earlierSuccess == EarlierSuccess.ACCEPTED && status == HTTP_CONFLICT && attempt > 1) {
                    // An earlier attempt succeeded on the server but its response was lost. Return the already-done
                    // response so the caller can treat it as an idempotent success.
                    return response;
                }
                if (status < 500) {
                    // A 4xx client error is a fixture logic bug. Fail fast without retrying.
                    throw new AssertionError(
                            "Failed to process request with error code %d: %s".formatted(status, request));
                }
                // A 5xx server error (e.g. Toxiproxy's 25-second TimeoutHandler) is transient. Retry. A retry that
                // eventually succeeds is normal operation, so this is only logged at DEBUG; a permanent failure is
                // surfaced by the NetworkControlUnavailableException thrown once all attempts are exhausted.
                log.debug(
                        "Toxiproxy request failed with status {} (attempt {}/{}): {}",
                        status,
                        attempt,
                        MAX_ATTEMPTS,
                        request);
            } catch (final IOException e) {
                lastException = e;
                log.debug("Toxiproxy request failed (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, request, e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while sending request %s".formatted(request), e);
            }

            if (attempt < MAX_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }
        throw new NetworkControlUnavailableException(
                "Failed to process request after %d attempts: %s".formatted(MAX_ATTEMPTS, request), lastException);
    }

    /**
     * Sleeps for the exponential backoff duration corresponding to the given attempt before the next retry.
     *
     * @param attempt the number of the attempt that just failed (1-based)
     */
    private static void sleepBeforeRetry(final int attempt) {
        final long backoffMillis = Math.min(MAX_BACKOFF.toMillis(), INITIAL_BACKOFF.toMillis() << (attempt - 1));
        try {
            Thread.sleep(backoffMillis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to retry a Toxiproxy request", e);
        }
    }

    @NonNull
    private static Proxy readProxyFromResponse(@NonNull final HttpResponse<String> response) {
        try {
            return MAPPER.readValue(response.body(), Proxy.class);
        } catch (final IOException e) {
            throw new AssertionError("Failed to parse proxy from response: %s".formatted(response.body()), e);
        }
    }

    @NonNull
    private static String toJson(@NonNull final Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (final JsonProcessingException e) {
            throw new AssertionError("Failed to serialize request body: %s".formatted(value), e);
        }
    }

    /**
     * Whether a repeat of a request that comes back reporting the work was already done should be accepted. This
     * happens when an earlier attempt succeeded on the server but its response was lost before reaching us, so the
     * retry finds the resource already present.
     */
    private enum EarlierSuccess {
        /** Accept it: the earlier, winning attempt was ours. Used for idempotent, create-style requests. */
        ACCEPTED,
        /** Do not accept it: treat every non-{@code 2xx} response as a failure. */
        NOT_ACCEPTED
    }
}
