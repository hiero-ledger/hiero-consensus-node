// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.metrics.IntegerPairAccumulator.AVERAGE;
import static org.apache.logging.log4j.Level.CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.common.metrics.IntegerPairAccumulator;
import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Metrics for preconsensus events.
 */
public class PcesFileManagerMetrics {

    private static final String CATEGORY = "platform";

    private static final LongGauge.Config PRECONSENSUS_EVENT_FILE_COUNT_CONFIG = new LongGauge.Config(
                    CATEGORY, "preconsensusEventFileCount")
            .withUnit("count")
            .withDescription("The number of preconsensus event files currently being stored");
    private final LongGauge preconsensusEventFileCount;

    private static final DoubleGauge.Config PRECONSENSUS_EVENT_FILE_AVERAGE_SIZE_MB_CONFIG = new DoubleGauge.Config(
                    CATEGORY, "preconsensusEventFileAverageSizeMB")
            .withUnit("megabytes")
            .withDescription("The average size of preconsensus event files, in megabytes.");
    private final DoubleGauge preconsensusEventFileAverageSizeMB;

    private static final DoubleGauge.Config PRECONSENSUS_EVENT_FILE_TOTAL_SIZE_GB_CONFIG = new DoubleGauge.Config(
                    CATEGORY, "preconsensusEventFileTotalSizeGB")
            .withUnit("gigabytes")
            .withDescription("The total size of all preconsensus event files, in gigabytes.");
    private final DoubleGauge preconsensusEventFileTotalSizeGB;

    private static final SpeedometerMetric.Config PRECONSENSUS_EVENT_FILE_RATE_CONFIG = new SpeedometerMetric.Config(
                    CATEGORY, "preconsensusEventFileRate")
            .withUnit("hertz")
            .withDescription("The number of preconsensus event files written per second.");
    private final SpeedometerMetric preconsensusEventFileRate;

    private static final RunningAverageMetric.Config PRECONSENSUS_EVENT_AVERAGE_FILE_SPAN_CONFIG =
            new RunningAverageMetric.Config(CATEGORY, "preconsensusEventAverageFileSpan")
                    .withDescription("The average span of preconsensus event files. Only reflects"
                            + "files written since the last restart.");
    private final RunningAverageMetric preconsensusEventAverageFileSpan;

    private static final RunningAverageMetric.Config PRECONSENSUS_EVENT_AVERAGE_UN_UTILIZED_FILE_SPAN_CONFIG =
            new RunningAverageMetric.Config(CATEGORY, "preconsensusEventAverageUnutilizedFileSpan")
                    .withDescription(
                            "The average unutilized span of preconsensus event files prior "
                                    + "to span compaction. Only reflects files written since the last restart. Smaller is better.");
    private final RunningAverageMetric preconsensusEventAverageUnUtilizedFileSpan;

    private static final LongGauge.Config PRECONSENSUS_EVENT_FILE_OLDEST_IDENTIFIER_CONFIG = new LongGauge.Config(
                    CATEGORY, "preconsensusEventFileOldestIdentifier")
            .withDescription("The oldest possible ancient indicator that is being stored in preconsensus event files.");
    private final LongGauge preconsensusEventFileOldestIdentifier;

    private static final LongGauge.Config PRECONSENSUS_EVENT_FILE_YOUNGEST_IDENTIFIER_CONFIG = new LongGauge.Config(
                    CATEGORY, "preconsensusEventFileYoungestIdentifier")
            .withDescription(
                    "The youngest possible ancient indicator that is being stored in preconsensus event files.");
    private final LongGauge preconsensusEventFileYoungestIdentifier;

    private static final LongGauge.Config PRECONSENSUS_EVENT_FILE_OLDEST_SECONDS_CONFIG = new LongGauge.Config(
                    CATEGORY, "preconsensusEventFileOldestSeconds")
            .withUnit("seconds")
            .withDescription("The age of the oldest preconsensus event file, in seconds.");
    private final LongGauge preconsensusEventFileOldestSeconds;
    private static final IntegerPairAccumulator.Config<Double> PCES_AVG_EVENT_SIZE =
            new IntegerPairAccumulator.Config<>(CATEGORY, "pcesAvgEventSize", Double.class, AVERAGE)
                    .withDescription("The average length in bytes of an event written in a pces file");
    private static final IntegerPairAccumulator.Config<Double> PCES_AVG_SYNC_DURATION =
            new IntegerPairAccumulator.Config<>(CATEGORY, "pcesAvgSyncDuration", Double.class, AVERAGE)
                    .withDescription("The amount of time it takes to complete a flush operation");
    private static final IntegerPairAccumulator.Config<Double> PCES_AVG_WRITE_DURATION =
            new IntegerPairAccumulator.Config<>(CATEGORY, "pcesAvgWriteDuration", Double.class, AVERAGE)
                    .withDescription("The amount of time it takes to complete a single write operation");
    private final IntegerPairAccumulator<Double> avgWriteMetric;
    private final IntegerPairAccumulator<Double> avgSyncMetric;
    private final IntegerPairAccumulator<Double> avgEventSizeMetric;

    /**
     * Construct preconsensus event metrics.
     *
     * @param metrics the metrics manager for the platform
     */
    public PcesFileManagerMetrics(final @NonNull Metrics metrics) {
        preconsensusEventFileCount = metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_COUNT_CONFIG);
        preconsensusEventFileAverageSizeMB = metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_AVERAGE_SIZE_MB_CONFIG);
        preconsensusEventFileTotalSizeGB = metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_TOTAL_SIZE_GB_CONFIG);
        preconsensusEventFileRate = metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_RATE_CONFIG);
        preconsensusEventAverageFileSpan = metrics.getOrCreate(PRECONSENSUS_EVENT_AVERAGE_FILE_SPAN_CONFIG);
        preconsensusEventAverageUnUtilizedFileSpan =
                metrics.getOrCreate(PRECONSENSUS_EVENT_AVERAGE_UN_UTILIZED_FILE_SPAN_CONFIG);
        preconsensusEventFileOldestIdentifier = metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_OLDEST_IDENTIFIER_CONFIG);
        preconsensusEventFileYoungestIdentifier =
                metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_YOUNGEST_IDENTIFIER_CONFIG);
        preconsensusEventFileOldestSeconds = metrics.getOrCreate(PRECONSENSUS_EVENT_FILE_OLDEST_SECONDS_CONFIG);
        this.avgWriteMetric = metrics.getOrCreate(PCES_AVG_WRITE_DURATION);
        this.avgSyncMetric = metrics.getOrCreate(PCES_AVG_SYNC_DURATION);
        this.avgEventSizeMetric = metrics.getOrCreate(PCES_AVG_EVENT_SIZE);
    }

    /**
     * Get the metric tracking the total number of preconsensus event files currently being tracked.
     */
    public LongGauge getPreconsensusEventFileCount() {
        return preconsensusEventFileCount;
    }

    /**
     * Get the metric tracking the average size of preconsensus event files.
     */
    public DoubleGauge getPreconsensusEventFileAverageSizeMB() {
        return preconsensusEventFileAverageSizeMB;
    }

    /**
     * Get the metric tracking the total size of all preconsensus event files.
     */
    public DoubleGauge getPreconsensusEventFileTotalSizeGB() {
        return preconsensusEventFileTotalSizeGB;
    }

    /**
     * Get the metric tracking the rate at which preconsensus event files are being created.
     */
    public SpeedometerMetric getPreconsensusEventFileRate() {
        return preconsensusEventFileRate;
    }

    /**
     * Get the metric tracking the average file span.
     */
    public RunningAverageMetric getPreconsensusEventAverageFileSpan() {
        return preconsensusEventAverageFileSpan;
    }

    /**
     * Get the metric tracking the average un-utilized file span.
     */
    public RunningAverageMetric getPreconsensusEventAverageUnUtilizedFileSpan() {
        return preconsensusEventAverageUnUtilizedFileSpan;
    }

    /**
     * Get the metric tracking the oldest possible ancient indicator that is being stored in preconsensus event files.
     */
    public LongGauge getPreconsensusEventFileOldestIdentifier() {
        return preconsensusEventFileOldestIdentifier;
    }

    /**
     * Get the metric tracking the youngest possible ancient indicator that is being stored in preconsensus event files.
     */
    public LongGauge getPreconsensusEventFileYoungestIdentifier() {
        return preconsensusEventFileYoungestIdentifier;
    }

    /**
     * Get the metric tracking the age of the oldest preconsensus event file, in seconds.
     */
    public LongGauge getPreconsensusEventFileOldestSeconds() {
        return preconsensusEventFileOldestSeconds;
    }

    /**
     * Initialize metrics given the files currently on disk.
     *
     * @param files
     */
    void initializeMetrics(PcesFileTracker files, Time time) {
        if (files.getFileCount() > 0) {
            getPreconsensusEventFileOldestIdentifier().set(files.getFirstFile().getLowerBound());
            getPreconsensusEventFileYoungestIdentifier().set(files.getLastFile().getUpperBound());
            final Duration age = Duration.between(files.getFirstFile().getTimestamp(), time.now());
            getPreconsensusEventFileOldestSeconds().set(age.toSeconds());
        } else {
            getPreconsensusEventFileOldestIdentifier().set(PcesFileManager.NO_LOWER_BOUND);
            getPreconsensusEventFileYoungestIdentifier().set(PcesFileManager.NO_LOWER_BOUND);
            getPreconsensusEventFileOldestSeconds().set(0);
        }
    }

    /**
     * Update metrics with the latest data on file size.
     *
     * @param files
     */
    void updateFileSizeMetrics(long totalFileByteCount, final PcesFileTracker files) {
        getPreconsensusEventFileCount().set(files.getFileCount());
        getPreconsensusEventFileTotalSizeGB().set(totalFileByteCount * UnitConstants.BYTES_TO_GIBIBYTES);

        if (files.getFileCount() > 0) {
            getPreconsensusEventFileAverageSizeMB()
                    .set(((double) totalFileByteCount) / files.getFileCount() * UnitConstants.BYTES_TO_MEBIBYTES);
        }
    }

    /**
     * reports the duration of the write operation
     */
    void endFileWrite(long size, long duration) {
        avgWriteMetric.update(asInt(duration), 1);
        this.avgEventSizeMetric.update(asInt(size), 1);
    }

    /**
     * reports the duration of the sync operation
     */
    void endFileSync(long duration) {
        avgSyncMetric.update(asInt(duration), 1);
    }

    /**
     * Returns the value of a long if is lower than Integer.MAX_VALUE or Integer.MAX_VALUE otherwise
     * @param val the value to check
     * @return the value of a long if is lower than Integer.MAX_VALUE or Integer.MAX_VALUE otherwise
     */
    private static int asInt(final long val) {
        return val > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) val;
    }
}
