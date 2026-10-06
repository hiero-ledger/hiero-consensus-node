// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.metrics;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.SnapshotEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * Snapshot processor that writes the snapshot to a file using Prometheus format.
 */
public class ToFilePrometheusExporter {

    private static final Logger logger = LogManager.getLogger(ToFilePrometheusExporter.class);

    private final NodeId selfId;
    private final Path outputFilePath;

    /**
     * Constructor
     *
     * @param selfId   the selfId
     * @param configuration   the configuration
     */
    public ToFilePrometheusExporter(@NonNull final NodeId selfId, @NonNull final Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        this.selfId = selfId;
        final MetricsConfig configData = configuration.getConfigData(MetricsConfig.class);
        final String fileName = "metrics.txt";
        final Path folderPath = Path.of(configData.csvOutputFolder());
        this.outputFilePath = folderPath.resolve(fileName);

        logger.info("InfluxDbLineProtocolWriter initialized: {}", outputFilePath);
    }

    /**
     * Handles snapshot events and writes them to the output file in InfluxDB line protocol format.
     *
     * @param snapshotEvent the snapshot event
     */
    public void handleSnapshots(@NonNull final SnapshotEvent snapshotEvent) {
        Objects.requireNonNull(snapshotEvent, "snapshotEvent must not be null");
        final Collection<Snapshot> snapshots = snapshotEvent.snapshots();
        if (snapshots.isEmpty()) {
            return;
        }

        try {
            ensureFolderExists();
            writeSnapshots(snapshots);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to write InfluxDB metrics", e);
        }
    }

    /**
     * Writes snapshots to the output file.
     */
    private void writeSnapshots(@NonNull final Collection<Snapshot> snapshots) throws IOException {

        final StandardOpenOption[] options = Files.exists(outputFilePath)
                ? new StandardOpenOption[] {StandardOpenOption.APPEND}
                : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};

        final List<Snapshot> sortSnapshots = new ArrayList<>(snapshots);
        sortSnapshots.sort(Comparator.comparing(s -> s.metric().getName()));
        try (final BufferedWriter writer = Files.newBufferedWriter(outputFilePath, options)) {
            final var buff = new StringBuffer();
            for (final Snapshot snapshot : sortSnapshots) {
                writeSnapshot(buff, snapshot, System.currentTimeMillis());
            }
            writer.write(buff.toString());
        }
    }

    /**
     * Writes a single snapshot in Prometheus exposition format.
     *
     * <p>Format: metric_name{label1="value1"} value timestamp_ms
     */
    private void writeSnapshot(
            @NonNull final StringBuffer writer, @NonNull final Snapshot snapshot, final long timestamp) {

        final Metric metric = snapshot.metric();
        final String metricName = sanitizeMeasurementName(metric.getName());

        // Export only VALUE type entries (skip MAX, MIN, STD_DEV)
        for (final SnapshotEntry entry : snapshot.entries()) {
            // Skip non-VALUE types when there are multiple entries
            if (snapshot.entries().size() > 1 && !entry.valueType().name().equals("VALUE")) {
                continue;
            }

            final Object value = entry.value();
            if (value == null) {
                continue; // Skip null values
            }

            // Format value
            final String fieldValue = formatValue(value);
            if (fieldValue == null) {
                continue; // Skip non-numeric values
            }

            // Build labels: only node (no category, no type)
            final String labels = "node=\"" + selfId.id() + "\"";

            writer.append(String.format("%s{%s} %s %d%n", metricName, labels, fieldValue, timestamp));
        }
    }

    /**
     * Sanitizes metric names for Prometheus (alphanumeric, underscores, colons).
     */
    @NonNull
    private String sanitizeMeasurementName(@NonNull final String name) {
        return name.replaceAll("[^a-zA-Z0-9_:]", "_");
    }

    /**
     * Formats a value for InfluxDB field value.
     * Returns null if the value cannot be represented as a number.
     */
    private String formatValue(@NonNull final Object value) {
        if (value instanceof Number number) {
            final double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return String.valueOf(d);
        }
        // Could support booleans (true/false -> 1/0) if needed
        return null;
    }

    /**
     * Ensures the output folder exists.
     */
    private void ensureFolderExists() throws IOException {
        final Path parent = outputFilePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }
}
