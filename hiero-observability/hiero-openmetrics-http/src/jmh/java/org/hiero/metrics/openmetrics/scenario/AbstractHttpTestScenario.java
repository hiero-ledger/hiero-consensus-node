// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.scenario;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.hiero.metrics.test.fixtures.framework.MetricFramework;

/**
 * Abstract base class for HTTP test scenarios using provided {@link MetricFramework}.
 * <p>
 * Extensions supposed to start an HTTP server on a random port and expose metrics endpoint
 *
 * @param <F> the type of the metric framework
 */
public abstract class AbstractHttpTestScenario<F extends MetricFramework> implements Closeable {

    private final int port;
    private final URI endpointUri;

    private final F framework;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    protected AbstractHttpTestScenario(F framework) {
        port = findPort();
        endpointUri = URI.create("http://localhost:" + port + getPath());

        this.framework = framework;
    }

    private int findPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Unable to find port", e);
        }
    }

    public final F getFramework() {
        return framework;
    }

    protected String getPath() {
        return "/metrics";
    }

    public void callEndpoint(boolean useGzip) throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder =
                HttpRequest.newBuilder().uri(endpointUri).GET();
        if (useGzip) {
            requestBuilder.header("Accept-Encoding", "gzip");
        }

        HttpResponse<Void> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected response status: " + response.statusCode());
        }
    }

    protected final int getPort() {
        return port;
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
