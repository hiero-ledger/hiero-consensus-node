// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.core.MetricKey;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.MetricsFacade;
import org.hiero.metrics.api.export.MetricsExportManager;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.api.utils.Unit;
import org.hiero.metrics.openmetrics.config.OpenMetricsHttpEndpointConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

public class OpenMetricsHttpEndpointTest {

    private static final HttpResponse.BodyHandler<String> BODY_HANDLER = responseInfo -> {
        var byteSubscriber = HttpResponse.BodySubscribers.ofByteArray();
        return HttpResponse.BodySubscribers.mapping(byteSubscriber, bytes -> {
            boolean isGzip = responseInfo
                    .headers()
                    .firstValue("Content-Encoding")
                    .map(v -> v.equalsIgnoreCase("gzip"))
                    .orElse(false);

            if (isGzip) {
                try (var bais = new ByteArrayInputStream(bytes);
                        var gis = new GZIPInputStream(bais)) {
                    byte[] decompressed = gis.readAllBytes();
                    return new String(decompressed, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        });
    };

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    public class RealMetricsTest extends BaseTest {

        private static final MetricKey<LongCounter> NO_LABELS_COUNTER = LongCounter.key("no_labels_counter");
        private static final MetricKey<LongCounter> LABELED_COUNTER = LongCounter.key("labeled_counter");

        private static final String METHOD_LABEL = "method";
        private static final String PATH_LABEL = "path";

        private static MetricRegistry registry;

        @BeforeAll
        public static void setUpAll() throws IOException {
            registry = MetricsFacade.createRegistry();

            globalInit(endpoint -> {
                final MetricsExportManager exportManager = MetricsFacade.createExportManager(endpoint);
                exportManager.manageMetricRegistry(registry);
            });
        }

        @AfterAll
        public static void tearDownAll() throws IOException {
            shutdown();
        }

        @Test
        @Order(1)
        public void noMetricsRegistered() {
            callAndVerify("# EOF\n");
        }

        @Test
        @Order(2)
        public void metricsRegisteredButNoObservations() {
            registry.register(LongCounter.builder(NO_LABELS_COUNTER)
                    .withUnit(Unit.BYTE_UNIT)
                    .withDescription("No labels counter"));
            registry.register(LongCounter.builder(LABELED_COUNTER)
                    .withUnit("requests")
                    .withDescription("Labeled counter")
                    .withDynamicLabelNames(METHOD_LABEL, PATH_LABEL));

            callAndVerify(
                    """
                    # TYPE no_labels_counter_byte counter
                    # UNIT no_labels_counter_byte byte
                    # HELP no_labels_counter_byte No labels counter
                    # TYPE labeled_counter_requests counter
                    # UNIT labeled_counter_requests requests
                    # HELP labeled_counter_requests Labeled counter
                    # EOF
                    """);
        }

        @Test
        @Order(3)
        public void metricsObserved() {
            registry.getMetric(NO_LABELS_COUNTER).getOrCreateNotLabeled().increment(10L);
            registry.getMetric(LABELED_COUNTER)
                    .getOrCreateLabeled(METHOD_LABEL, "GET", PATH_LABEL, "/api/resource")
                    .increment(5L);
            registry.getMetric(LABELED_COUNTER)
                    .getOrCreateLabeled(METHOD_LABEL, "POST", PATH_LABEL, "/api/resource")
                    .increment(8L);

            callAndVerify(
                    """
                    # TYPE no_labels_counter_byte counter
                    # UNIT no_labels_counter_byte byte
                    # HELP no_labels_counter_byte No labels counter
                    no_labels_counter_byte_total 10
                    # TYPE labeled_counter_requests counter
                    # UNIT labeled_counter_requests requests
                    # HELP labeled_counter_requests Labeled counter
                    labeled_counter_requests_total{method="GET",path="/api/resource"} 5
                    labeled_counter_requests_total{method="POST",path="/api/resource"} 8
                    # EOF
                    """);
        }

        private void callAndVerify(String expectedBody) {
            verifyResponse(callMetrics(false), expectedBody);
            verifyResponse(callMetrics(true), expectedBody);
        }

        private void verifyResponse(HttpResponse<String> response, String expectedBody) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(expectedBody);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    public class MockMetricsTest extends BaseTest {

        @Mock
        private Supplier<Optional<MetricsSnapshot>> snapshotSupplier;

        @BeforeAll
        public static void setUpAll() throws IOException {
            globalInit(null);
        }

        @AfterAll
        public static void tearDownAll() throws IOException {
            shutdown();
        }

        @BeforeEach
        public void setUp() {
            testInit(endpoint -> endpoint.init(snapshotSupplier));
        }

        @AfterEach
        public void afterEach() {
            verifyNoMoreInteractions(snapshotSupplier);
        }

        @Test
        public void noSnapshots() {
            when(snapshotSupplier.get()).thenReturn(Optional.empty());
            HttpResponse<String> response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.body()).isEmpty();

            verify(snapshotSupplier, times(1)).get();
        }

        @Test
        public void exceptionSnapshotting() {
            when(snapshotSupplier.get()).thenThrow(new RuntimeException());
            HttpResponse<String> response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEmpty();
        }

        @Test
        public void concurrentRequest() throws ExecutionException, InterruptedException {
            final CountDownLatch firstRequestStarted = new CountDownLatch(1);
            final CountDownLatch secondRequestFinished = new CountDownLatch(1);

            // simulate a long-running snapshotting in the first request until the second request is done
            when(snapshotSupplier.get()).thenAnswer(invocation -> {
                firstRequestStarted.countDown(); // Signal that lock is acquired
                secondRequestFinished.await(1, TimeUnit.SECONDS); // Wait until the concurrent request is done
                return Optional.empty();
            });

            // Start the first request asynchronously
            CompletableFuture<HttpResponse<String>> firstRequestResponseFuture =
                    CompletableFuture.supplyAsync(() -> callMetrics(false));

            // Wait until the first request acquires the lock
            firstRequestStarted.await(1, TimeUnit.SECONDS);
            // Now make the second concurrent request
            HttpResponse<String> concurrentResponse = callMetrics(false);
            // Allow the first request to finish
            secondRequestFinished.countDown();

            assertThat(concurrentResponse.statusCode()).isEqualTo(429);
            assertThat(concurrentResponse.body()).isEmpty();

            HttpResponse<String> firstRequestResponse = firstRequestResponseFuture.get();
            assertThat(firstRequestResponse.statusCode()).isEqualTo(204);
            assertThat(firstRequestResponse.body()).isEmpty();

            verify(snapshotSupplier, times(1)).get();
        }
    }

    abstract static class BaseTest {

        private static HttpClient httpClient;
        private static OpenMetricsHttpEndpoint endpoint;
        private static URI uri;

        protected static void globalInit(@Nullable Consumer<OpenMetricsHttpEndpoint> initialization)
                throws IOException {
            httpClient = HttpClient.newHttpClient();

            final String path = "/metrics";
            final int port = findFreePort();
            final OpenMetricsHttpEndpointConfig cfg = new OpenMetricsHttpEndpointConfig(true, port, path, 0);
            endpoint = new OpenMetricsHttpEndpoint(cfg);
            if (initialization != null) {
                initialization.accept(endpoint);
            }

            uri = URI.create("http://localhost:" + port + path);
        }

        protected void testInit(@Nullable Consumer<OpenMetricsHttpEndpoint> initialization) {
            if (initialization != null) {
                initialization.accept(endpoint);
            }
        }

        // helper to find an ephemeral free port
        private static int findFreePort() {
            try (ServerSocket s = new ServerSocket(0)) {
                return s.getLocalPort();
            } catch (IOException e) {
                throw new RuntimeException("Unable to find free port", e);
            }
        }

        protected static void shutdown() throws IOException {
            if (endpoint != null) {
                endpoint.close();
                endpoint = null;
            }
        }

        protected HttpResponse<String> callMetrics(boolean useGzip) {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(1))
                        .GET();

                if (useGzip) {
                    reqBuilder.header("Accept-Encoding", "gzip");
                }

                return httpClient.send(reqBuilder.build(), BODY_HANDLER);
            } catch (Exception e) {
                throw new RuntimeException("Error calling metrics endpoint", e);
            }
        }
    }
}
