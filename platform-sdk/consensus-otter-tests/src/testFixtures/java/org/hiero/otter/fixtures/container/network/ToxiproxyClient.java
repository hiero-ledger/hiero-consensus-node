// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.network;

import static com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * A client for interacting with the Toxiproxy control server REST API.
 * This client allows creating and updating proxies to simulate network conditions.
 */
public class ToxiproxyClient {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final URI createProxyUri;
    private final URI updateProxyUri;

    /**
     * Constructs a new ToxiproxyClient instance.
     *
     * @param host the host on which the Toxiproxy control server is running
     * @param controlPort the port on which the Toxiproxy control server is running
     */
    public ToxiproxyClient(@NonNull final String host, final int controlPort) {
        this.createProxyUri = URI.create(String.format("http://%s:%d/proxies", host, controlPort));
        this.updateProxyUri = URI.create(String.format("http://%s:%d/proxies/", host, controlPort));
    }

    /**
     * Creates a new proxy with the specified configuration.
     *
     * @param proxy the proxy configuration to create
     * @return the created proxy as it is stored on the server
     */
    @NonNull
    public Proxy createProxy(@NonNull final Proxy proxy) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(createProxyUri)
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(proxy)))
                    .build();
            final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            return readProxyFromResponse(response);
        } catch (final IOException | InterruptedException e) {
            throw new AssertionError("Exception while updating proxy", e);
        }
    }

    /**
     * Updates an existing proxy with the specified configuration.
     *
     * @param proxy the proxy configuration to update
     * @return the updated proxy as it is stored on the server
     */
    @NonNull
    public Proxy updateProxy(@NonNull final Proxy proxy) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(updateProxyUri.resolve(proxy.name()))
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(proxy)))
                    .build();
            final HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            return readProxyFromResponse(response);
        } catch (final IOException | InterruptedException e) {
            throw new AssertionError("Exception while updating proxy", e);
        }
    }

    private Proxy readProxyFromResponse(@NonNull final HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to process request with error code %d: %s"
                    .formatted(response.statusCode(), response.request()));
        }
        return MAPPER.readValue(response.body(), Proxy.class);
    }
}
