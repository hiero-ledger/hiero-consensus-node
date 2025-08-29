// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.services.bdd.junit.hedera.subprocess.StatusLookupAttempt.newPrometheusAttempt;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * A client for querying the status of a Hedera node via its Prometheus endpoint.
 * The Prometheus endpoint returns text with lines such as,
 * <pre>
 * # HELP platform_PlatformStatus 0=NO_VALUE 1=STARTING_UP 2=ACTIVE 4=BEHIND 5=FREEZING 6=FREEZE_COMPLETE 7=REPLAYING_EVENTS 8=OBSERVING 9=CHECKING 10=RECONNECT_COMPLETE 11=CATASTROPHIC_FAILURE
 * # TYPE platform_PlatformStatus gauge
 * platform_PlatformStatus{node="0",} 2.0
 * </pre>
 * Which means we can get the platform status as the value of the {@code platform_PlatformStatus} gauge.
 */
public class PrometheusClient {
    private static final Pattern PROM_PLATFORM_STATUS_HELP_PATTERN =
            Pattern.compile("# HELP platform_PlatformStatus (.*)");
    private static final Pattern PROM_PLATFORM_STATUS_PATTERN =
            Pattern.compile("platform_PlatformStatus\\{.*\\} (\\d+)\\.\\d+");
    private static final Pattern PROM_OPS_DURATION_THROTTLE_PERCENT_USED_METRIC_PATTERN =
            Pattern.compile("app_consOpsDurationPercentUsed\\{.*\\}.*");
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Returns the Platform status of the node with Prometheus endpoint at the given
     * port; or null if the node is not available.
     *
     * @param port the port of the node's Prometheus endpoint
     * @return the status lookup attempt
     */
    public StatusLookupAttempt statusFromLocalEndpoint(final int port) {
        String failureReason = null;
        final Map<String, String> statusMap = new HashMap<>();
        final AtomicReference<String> statusKey = new AtomicReference<>();
        try {
            final var request = prometheusRequest(port);
            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final var reader = new BufferedReader(new StringReader(response.body()));
            reader.lines().forEach(line -> {
                fillStatusMapIfHelp(line, statusMap);
                setStatusIfCurrent(line, statusKey);
            });
            if (statusMap.get(statusKey.get()) == null) {
                failureReason = "Legend " + statusMap + " missing current status key '" + statusKey.get() + "'";
            }
        } catch (IOException ignore) {
            // We allow unavailable statuses
            failureReason = "Prometheus endpoint not available at port " + port;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return newPrometheusAttempt(statusMap.get(statusKey.get()), failureReason);
    }

    /**
     * Returns a list of throttle duration metrics from the Prometheus endpoint
     * @param port the prometheus port
     * @return a list with string representations of throttle duration metrics
     */
    public List<String> getOpsDurationThrottlePercentUsedMetrics(final int port) {
        return getMetrics(port, PROM_OPS_DURATION_THROTTLE_PERCENT_USED_METRIC_PATTERN);
    }

    private List<String> getMetrics(final int port, final Pattern pattern) {
        try {
            final var list = new ArrayList<String>();
            final var request = prometheusRequest(port);
            final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            final var reader = new BufferedReader(new StringReader(response.body()));
            reader.lines().forEach(line -> {
                final var matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    list.add(line);
                }
            });
            return list;
        } catch (IOException ignore) {
            // We allow unavailable statuses
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void setStatusIfCurrent(@NonNull final String line, @NonNull final AtomicReference<String> statusKey) {
        final var matcher = PROM_PLATFORM_STATUS_PATTERN.matcher(line);
        if (matcher.matches()) {
            statusKey.set(matcher.group(1));
        }
    }

    private void fillStatusMapIfHelp(@NonNull final String line, @NonNull final Map<String, String> statusMap) {
        final var helpMatcher = PROM_PLATFORM_STATUS_HELP_PATTERN.matcher(line);
        if (helpMatcher.matches()) {
            final var kvPairs = helpMatcher.group(1).split(" ");
            for (final var kvPair : kvPairs) {
                final var parts = kvPair.split("=");
                statusMap.put(parts[0], parts[1]);
            }
        }
    }

    private HttpRequest prometheusRequest(final int port) {
        try {
            return HttpRequest.newBuilder()
                    .uri(new URI("http://localhost:" + port))
                    .GET()
                    .build();
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
