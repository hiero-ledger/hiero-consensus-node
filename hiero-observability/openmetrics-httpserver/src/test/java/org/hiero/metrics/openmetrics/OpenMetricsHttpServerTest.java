// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.zip.GZIPInputStream;
import org.hiero.metrics.DoubleGauge;
import org.hiero.metrics.LongCounter;
import org.hiero.metrics.core.MetricKey;
import org.hiero.metrics.core.MetricRegistry;
import org.hiero.metrics.core.MetricsExporter;
import org.hiero.metrics.core.Unit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OpenMetricsHttpServerTest {

    private static final MetricKey<DoubleGauge> NO_LABELS_GAUGE = DoubleGauge.key("no_labels_gauge");
    private static final MetricKey<LongCounter> LABELED_COUNTER = LongCounter.key("labeled_counter");

    private static final String METHOD_LABEL = "method";
    private static final String PATH_LABEL = "path";

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private static URI uri;
    private static MetricRegistry registry;

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

    @BeforeAll
    static void init() {
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

        MetricsExporter server = new OpenMetricsHttpServerFactory().createExporter(List.of(), config);
        if (server == null) {
            throw new IllegalStateException("Failed to create OpenMetricsHttpServer");
        }
        uri = URI.create("http://localhost:" + port + path);
        registry = MetricRegistry.builder().setMetricsExporter(server).build();
    }

    @AfterAll
    static void shutdown() throws IOException {
        httpClient.close();
        registry.close();
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
        callAndVerify(expectedBody, false);
        callAndVerify(expectedBody, true);
    }

    private void callAndVerify(String expectedBody, boolean useGzip) {
        final HttpRequest.Builder requestBuilder =
                HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(1)).GET();

        if (useGzip) {
            requestBuilder.header("Accept-Encoding", "gzip");
        }

        final HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), BODY_HANDLER);
        } catch (Exception e) {
            throw new RuntimeException("Error sending request", e);
        }

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expectedBody);

        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/openmetrics-text; version=1.0.0; charset=utf-8");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(response.headers().firstValue("Vary")).hasValue("Accept-Encoding");

        if (useGzip) {
            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        } else {
            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        }
    }
}
