// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.MetricsExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class OpenMetricsHttpServerMockTest {

    private static final OpenMetricsHttpServerFactory factory = new OpenMetricsHttpServerFactory();

    private MetricsExporter openMetricsServer;
    private URI uri;
    private MetricRegistry registry;
    private HttpClient httpClient;

    @Mock
    private Supplier<MetricRegistrySnapshot> snapshotSupplier;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();

        final int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Unable to find free port", e);
        }

        final String path = "/metrics";
        Configuration config = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue("metrics.exporter.openmetrics.http.port", Integer.toString(port))
                .withValue("metrics.exporter.openmetrics.http.path", path)
                .build();

        openMetricsServer = factory.createExporter(List.of(), config);
        uri = URI.create("http://localhost:" + port + path);
        registry =
                MetricRegistry.builder().setMetricsExporter(openMetricsServer).build();

        openMetricsServer.setSnapshotSupplier(snapshotSupplier);
    }

    @AfterEach
    void afterEach() throws IOException {
        verifyNoMoreInteractions(snapshotSupplier);
        httpClient.close();
        registry.close();
    }

    @Test
    void testNoSupplierSetYet() {
        openMetricsServer.setSnapshotSupplier(null);
        HttpResponse<String> response = callMetrics();
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();

        // subsequent request should also return 204
        response = callMetrics();
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void testExceptionThrownWhileSnapshotting() {
        when(snapshotSupplier.get()).thenThrow(new RuntimeException());

        HttpResponse<String> response = callMetrics();

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEmpty();
        verify(snapshotSupplier).get();
    }

    @Test
    void testExceptionWhenNoAllowedHttpMethod() {
        String[] notAllowedMethods = {"POST", "PUT", "DELETE", "PATCH", "OPTIONS"};

        for (String notAllowedMethod : notAllowedMethods) {
            HttpResponse<String> response =
                    send(newReqeust().method(notAllowedMethod, HttpRequest.BodyPublishers.noBody()));

            assertThat(response.statusCode())
                    .as("Method is not allowed: " + notAllowedMethod)
                    .isEqualTo(405);
            assertThat(response.headers().firstValue("Allow")).hasValue("GET, HEAD");
            assertThat(response.body()).isEmpty();
        }
    }

    @Test
    void testConcurrentGetRequest() throws ExecutionException, InterruptedException {
        final CountDownLatch firstRequestStarted = new CountDownLatch(1);
        final CountDownLatch secondRequestFinished = new CountDownLatch(1);

        // simulate a long-running snapshotting in the first request until the second request is done
        when(snapshotSupplier.get()).thenAnswer(invocation -> {
            firstRequestStarted.countDown(); // Signal that lock is acquired
            secondRequestFinished.await(1, TimeUnit.SECONDS); // Wait until the concurrent request is done
            return new MetricRegistrySnapshot();
        });

        // Start the first request asynchronously
        CompletableFuture<HttpResponse<String>> firstRequestResponseFuture =
                CompletableFuture.supplyAsync(this::callMetrics);

        // Wait until the first request acquires the lock
        firstRequestStarted.await(1, TimeUnit.SECONDS);
        // Now make the second concurrent request
        HttpResponse<String> concurrentResponse = callMetrics();
        // Allow the first request to finish
        secondRequestFinished.countDown();

        assertThat(concurrentResponse.statusCode()).isEqualTo(429);
        assertThat(concurrentResponse.headers().firstValue("Retry-After")).hasValue("3");
        assertThat(concurrentResponse.body()).isEmpty();

        HttpResponse<String> firstRequestResponse = firstRequestResponseFuture.get();
        assertThat(firstRequestResponse.statusCode()).isEqualTo(200);
        assertThat(firstRequestResponse.body()).isEqualTo("# EOF\n");

        verify(snapshotSupplier, times(1)).get();
    }

    @Test
    void testConcurrentHeadRequests() throws InterruptedException, ExecutionException {
        int requestsCount = 5;
        List<CompletableFuture<HttpResponse<String>>> responseFutures = IntStream.range(0, requestsCount)
                .mapToObj(i ->
                        CompletableFuture.supplyAsync(() -> send(newReqeust().HEAD())))
                .toList();

        for (CompletableFuture<HttpResponse<String>> responseFuture : responseFutures) {
            HttpResponse<String> response = responseFuture.get();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();

            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValue("application/openmetrics-text; version=1.0.0; charset=utf-8");
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(response.headers().firstValue("Vary")).hasValue("Accept-Encoding");
        }

        verify(snapshotSupplier, never()).get();
    }

    private HttpRequest.Builder newReqeust() {
        return HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(1));
    }

    private HttpResponse<String> send(HttpRequest.Builder requestBuilder) {
        try {
            return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Error sending request", e);
        }
    }

    private HttpResponse<String> callMetrics() {
        return send(newReqeust().GET());
    }
}
