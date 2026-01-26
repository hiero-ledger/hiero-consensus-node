// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import org.hiero.metrics.DoubleGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricRegistrySnapshot;
import org.hiero.metrics.core.Unit;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

public class OpenMetricsHttpServerTest {

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
    class RealMetricsTest extends BaseTest {

        private static MetricRegistry registry;

        private static final MetricKey<DoubleGauge> NO_LABELS_GAUGE = DoubleGauge.key("no_labels_gauge");
        private static final MetricKey<LongCounter> LABELED_COUNTER = LongCounter.key("labeled_counter");

        private static final String METHOD_LABEL = "method";
        private static final String PATH_LABEL = "path";

        @BeforeAll
        static void setUpAll() throws IOException {
            globalInit();
            registry = MetricRegistry.builder().setMetricsExporter(server).build();
        }

        @Test
        @Order(1)
        void testNoMetricsRegistered() {
            callAndVerify("# EOF\n");
        }

        @Test
        @Order(2)
        void testMetricsRegisteredButNoObservations() {
            registry.register(
                    DoubleGauge.builder(NO_LABELS_GAUGE).setUnit(Unit.BYTE_UNIT).setDescription("No labels gauge"));
            registry.register(LongCounter.builder(LABELED_COUNTER)
                    .setUnit("requests")
                    .setDescription("Labeled counter")
                    .addDynamicLabelNames(METHOD_LABEL, PATH_LABEL));

            callAndVerify("""
                    # TYPE no_labels_gauge_byte gauge
                    # UNIT no_labels_gauge_byte byte
                    # HELP no_labels_gauge_byte No labels gauge
                    # TYPE labeled_counter_requests counter
                    # UNIT labeled_counter_requests requests
                    # HELP labeled_counter_requests Labeled counter
                    # EOF
                    """);
        }

        @Test
        @Order(3)
        void testMetricsObserved() {
            registry.getMetric(NO_LABELS_GAUGE).getOrCreateNotLabeled().set(10.5);
            registry.getMetric(LABELED_COUNTER)
                    .getOrCreateLabeled(METHOD_LABEL, "GET", PATH_LABEL, "/api/resource")
                    .increment(5L);
            registry.getMetric(LABELED_COUNTER)
                    .getOrCreateLabeled(METHOD_LABEL, "POST", PATH_LABEL, "/api/resource")
                    .increment(8L);

            callAndVerify("""
                    # TYPE no_labels_gauge_byte gauge
                    # UNIT no_labels_gauge_byte byte
                    # HELP no_labels_gauge_byte No labels gauge
                    no_labels_gauge_byte 10.5
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
    class MockMetricsTest extends BaseTest {

        @Mock
        private Supplier<MetricRegistrySnapshot> snapshotSupplier;

        @BeforeEach
        void setUp() {
            server.setSnapshotSupplier(snapshotSupplier);
        }

        @AfterEach
        void afterEach() {
            verifyNoMoreInteractions(snapshotSupplier);
        }

        @Test
        void testNoSupplierSetYet() {
            server.setSnapshotSupplier(null);
            HttpResponse<String> response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.body()).isEmpty();

            // subsequent request should also return 204
            response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.body()).isEmpty();
        }

        @Test
        void testExceptionThrownWhileSnapshotting() {
            when(snapshotSupplier.get()).thenThrow(new RuntimeException());
            HttpResponse<String> response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(200); // because status is already commited
            assertThat(response.body()).isEmpty();

            // subsequent request should also return 500
            response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();

            verify(snapshotSupplier, times(2)).get();
        }

        @ParameterizedTest
        @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
        void testExceptionWhenNoAllowedHttpMethod(String method) {
            HttpResponse<String> response = send(HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(1))
                    .method(method, HttpRequest.BodyPublishers.noBody()));

            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(response.headers().firstValue("Allow")).hasValue("GET, HEAD");
            assertThat(response.body()).isEmpty();
        }

        @Test
        void testConcurrentRequest() throws ExecutionException, InterruptedException {
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
                    CompletableFuture.supplyAsync(() -> callMetrics(false));

            // Wait until the first request acquires the lock
            firstRequestStarted.await(1, TimeUnit.SECONDS);
            // Now make the second concurrent request
            HttpResponse<String> concurrentResponse = callMetrics(false);
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
        void testEmptyMetricsHeaders() {
            when(snapshotSupplier.get()).thenReturn(new MetricRegistrySnapshot());

            HttpResponse<String> response = callMetrics(false);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("# EOF\n");
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValue("application/openmetrics-text; version=1.0.0; charset=utf-8");
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(response.headers().firstValue("Vary")).hasValue("Accept-Encoding");

            response = callMetrics(true);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("# EOF\n");
            assertThat(response.headers().firstValue("Content-Type"))
                    .hasValue("application/openmetrics-text; version=1.0.0; charset=utf-8");
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
            assertThat(response.headers().firstValue("Vary")).hasValue("Accept-Encoding");

            verify(snapshotSupplier, times(2)).get();
        }
    }

    abstract static class BaseTest {

        private static final OpenMetricsHttpServerFactory factory = new OpenMetricsHttpServerFactory();
        private static HttpClient httpClient;
        protected static OpenMetricsHttpServer server;
        protected static URI uri;

        @BeforeAll
        protected static void globalInit() {
            final String path = "/metrics";
            final int port = findFreePort();

            Configuration config = ConfigurationBuilder.create()
                    .autoDiscoverExtensions()
                    .withValue("metrics.exporter.openmetrics.http.port", Integer.toString(port))
                    .withValue("metrics.exporter.openmetrics.http.path", path)
                    .build();

            server = (OpenMetricsHttpServer) factory.createExporter(List.of(), config);
            httpClient = HttpClient.newHttpClient();
            uri = URI.create("http://localhost:" + port + path);
        }

        // helper to find an ephemeral free port
        private static int findFreePort() {
            try (ServerSocket s = new ServerSocket(0)) {
                return s.getLocalPort();
            } catch (IOException e) {
                throw new RuntimeException("Unable to find free port", e);
            }
        }

        @AfterAll
        protected static void shutdown() {
            if (server != null) {
                server.close();
                server = null;
            }
        }

        protected HttpResponse<String> send(HttpRequest.Builder requestBuilder) {
            try {
                return httpClient.send(requestBuilder.build(), BODY_HANDLER);
            } catch (Exception e) {
                throw new RuntimeException("Error sending request", e);
            }
        }

        protected HttpResponse<String> callMetrics(boolean useGzip) {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(1))
                    .GET();

            if (useGzip) {
                reqBuilder.header("Accept-Encoding", "gzip");
            }

            return send(reqBuilder);
        }
    }
}
