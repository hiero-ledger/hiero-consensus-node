// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.metrics.BlockStreamMetrics;
import com.swirlds.metrics.api.MetricConfig;
import com.swirlds.metrics.api.Metrics;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * This is NOT a real test for the BlockStreamMetrics class. Instead, this is a helper class that can be used to
 * "export" all the registered metrics related to block streaming. The "test" prints the metric names, type, and
 * description separated by a pipe ('|'). This output can then be copied into the metrics documentation and formatted
 * into a Markdown table.
 */
@ExtendWith(MockitoExtension.class)
public class BlockStreamMetricsExportTest {

    @Test
    @Disabled("Not an actual test; used to scrape all registered metrics for documentation updates")
    void test() {
        final SortedMap<String, MetricInfo> map = new TreeMap<>();

        final Metrics metrics = mock(Metrics.class);
        doAnswer(inv -> {
                    final MetricConfig<?, ?> cfg = inv.getArgument(0);
                    final String fullName = cfg.getCategory() + "_" + cfg.getName();
                    final MetricInfo info = new MetricInfo(
                            cfg.getCategory(),
                            cfg.getName(),
                            cfg.getResultClass().getTypeName(),
                            cfg.getDescription());
                    map.put(fullName, info);
                    return null;
                })
                .when(metrics)
                .getOrCreate(any(MetricConfig.class));

        new BlockStreamMetrics(metrics);
        printMetricsTables(map);
        System.out.println(map.size() + " metrics found");
    }

    private void printMetricsTables(final Map<String, MetricInfo> metrics) {
        final SortedMap<String, String> bufferMetrics = new TreeMap<>();
        final SortedMap<String, String> connectivityMetrics = new TreeMap<>();
        final SortedMap<String, String> recvMetrics = new TreeMap<>();
        final SortedMap<String, String> sendMetrics = new TreeMap<>();
        final SortedMap<String, String> hashesMetrics = new TreeMap<>();

        metrics.forEach((fullName, info) -> {
            final String typeString = toTypeString(info.type);
            final String lcName = info.name.toLowerCase();
            final String tableRow = toTableRow(fullName, typeString, info.description);

            if (lcName.startsWith("buffer_")) {
                bufferMetrics.put(fullName, tableRow);
            } else if (lcName.contains("connsend_")) {
                sendMetrics.put(fullName, tableRow);
            } else if (lcName.contains("connrecv_")) {
                recvMetrics.put(fullName, tableRow);
            } else if (lcName.contains("conn_")) {
                connectivityMetrics.put(fullName, tableRow);
            } else if (lcName.contains("recordhashes_")) {
                hashesMetrics.put(fullName, tableRow);
            } else {
                throw new IllegalArgumentException("Unknown metric: " + fullName);
            }
        });

        System.out.println("Buffer Metrics");
        bufferMetrics.values().forEach(System.out::println);

        System.out.println("Connectivity Metrics");
        connectivityMetrics.values().forEach(System.out::println);

        System.out.println("Connection Receive Metrics");
        recvMetrics.values().forEach(System.out::println);

        System.out.println("Connection Send Metrics");
        sendMetrics.values().forEach(System.out::println);

        System.out.println("Record Hashes Metrics");
        hashesMetrics.values().forEach(System.out::println);
    }

    private static String toTableRow(final String fullName, final String typeString, final String description) {
        return "| `" + fullName + "` | " + typeString + " | " + description + " |";
    }

    private static String toTypeString(final String type) {
        if (type.endsWith("LongGauge")) {
            return "Gauge (long)";
        } else if (type.endsWith("Counter")) {
            return "Counter";
        } else if (type.endsWith("RunningAverageMetric")) {
            return "Running average";
        } else if (type.endsWith("DoubleGauge")) {
            return "Gauge (double)";
        }

        throw new IllegalArgumentException("unknown type: " + type);
    }

    private record MetricInfo(String category, String name, String type, String description) {}
}
