// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.util;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.SwirldsPlatform;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility methods for working with Metrics documentation.
 */
public final class MetricsDocUtils {

    private static final Logger logger = LogManager.getLogger(MetricsDocUtils.class);

    /**
     * Field separator to use for Metrics document generation
     */
    static final char FIELD_SEPARATOR = '\t';

    private MetricsDocUtils() {}

    /**
     * Writes all metrics information to a file in the base directory. This method is to be called after the local
     * platforms are created with the platform {@code Metrics}. Both global metrics and platform metrics will be
     * included in the output file.
     *
     * @param globalMetrics the global {@code Metrics}
     * @param platforms     the collection of {@code SwirldsPlatform}s
     * @param configuration the {@code Configuration}
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code globalMetrics}</li>
     *       <li>{@code platforms}</li>
     *       <li>{@code configuration}</li>
     *     </ul>
     *
     */
    public static void writeMetricsDocumentToFile(
            final Metrics globalMetrics,
            final Collection<SwirldsPlatform> platforms,
            final Configuration configuration) {
        Objects.requireNonNull(globalMetrics, "globalMetrics must not be null");
        Objects.requireNonNull(platforms, "platforms must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        // Combine global metrics and platform metrics without duplicates
        final Set<Metric> combinedMetrics = new HashSet<>(globalMetrics.getAll());
        for (final SwirldsPlatform platform : platforms) {
            combinedMetrics.addAll(platform.getContext().getMetrics().getAll());
        }

        final String metricsContents = generateMetricsDocContentsInTSV(combinedMetrics);
        final String filePath = FileUtils.getUserDir()
                + File.separator
                + configuration.getConfigData(MetricsConfig.class).metricsDocFileName();

        try (final OutputStream outputStream = new FileOutputStream(filePath)) {
            outputStream.write(metricsContents.getBytes(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            logger.error(EXCEPTION.getMarker(), "Failed to write metrics information to file {}", filePath, e);
        }
    }

    /**
     * Generates the Metrics document contents with the given collection of {@link Metric}s in the pre-defined TSV
     * format and returns the contents as {@code String}. The metrics data will be sorted in alphabetical order of their
     * identifiers.
     *
     * @param metrics a collection of {@code Metric}s
     * @return the metrics document contents
     * @throws NullPointerException in case {@code metrics} parameter is {@code null}
     */
    private static String generateMetricsDocContentsInTSV(final Collection<Metric> metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");

        final List<Metric> sortedMetrics = metrics.stream()
                .sorted((x, y) -> x.getIdentifier().compareToIgnoreCase(y.getIdentifier()))
                .toList();

        final StringBuilder sb = new StringBuilder();
        sb.append("Category").append(FIELD_SEPARATOR);
        sb.append("Identifier").append(FIELD_SEPARATOR);
        sb.append("Name").append(FIELD_SEPARATOR);
        sb.append("Metric Type").append(FIELD_SEPARATOR);
        sb.append("Data Type").append(FIELD_SEPARATOR);
        sb.append("Unit").append(FIELD_SEPARATOR);
        sb.append("Description").append(System.lineSeparator());

        for (final Metric metric : sortedMetrics) {
            sb.append(metric.getCategory()).append(FIELD_SEPARATOR);
            sb.append(metric.getIdentifier()).append(FIELD_SEPARATOR);
            sb.append(metric.getName()).append(FIELD_SEPARATOR);
            sb.append(metric.getMetricType()).append(FIELD_SEPARATOR);
            sb.append(metric.getDataType()).append(FIELD_SEPARATOR);
            sb.append(metric.getUnit()).append(FIELD_SEPARATOR);
            sb.append(metric.getDescription()).append(System.lineSeparator());
        }

        return sb.toString();
    }
}
