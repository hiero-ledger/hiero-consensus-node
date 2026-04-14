// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.LABEL_COMPACTION_LEVEL;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.LABEL_STORE_NAME;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FILES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FILES_GARBAGE_RATIO;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FILES_SIZE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.summarizingLong;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LongSummaryStatistics;
import java.util.Map;
import org.hiero.metrics.DoubleAccumulatorGauge;
import org.hiero.metrics.LongGauge;
import org.hiero.metrics.core.MetricRegistry;

public final class FileCollectionMetrics {

    private final DataFileCollection dataFileCollection;
    private final LongGauge filesCount;
    private final LongGauge filesSize;
    private final DoubleAccumulatorGauge garbageRatio;

    public FileCollectionMetrics(@NonNull DataFileCollection dataFileCollection, @NonNull MetricRegistry registry) {
        this.dataFileCollection = dataFileCollection;
        filesCount = registry.getMetric(METRIC_KEY_FILES_CNT);
        filesSize = registry.getMetric(METRIC_KEY_FILES_SIZE);
        garbageRatio = registry.getMetric(METRIC_KEY_FILES_GARBAGE_RATIO);
    }

    public void updateFileMetrics() {
        final Map<Integer, LongSummaryStatistics> statsByLevel = dataFileCollection
                .streamAllCompletedFiles()
                .collect(groupingBy(
                        fileReader -> fileReader.getMetadata().getCompactionLevel(),
                        mapping(DataFileReader::getSize, summarizingLong(Long::longValue))));

        final String storeName = dataFileCollection.getStoreName();
        statsByLevel.forEach((level, levelStats) -> {
            final String levelString = String.valueOf(level);
            filesCount
                    .getOrCreateLabeled(LABEL_STORE_NAME, storeName, LABEL_COMPACTION_LEVEL, levelString)
                    .set(levelStats.getCount());
            filesSize
                    .getOrCreateLabeled(LABEL_STORE_NAME, storeName, LABEL_COMPACTION_LEVEL, levelString)
                    .set(levelStats.getSum());
        });
    }

    public void updateScanMetrics(double levelGarbageRatio, int level) {
        garbageRatio
                .getOrCreateLabeled(
                        LABEL_STORE_NAME,
                        dataFileCollection.getStoreName(),
                        LABEL_COMPACTION_LEVEL,
                        String.valueOf(level))
                .accumulate(levelGarbageRatio);
    }
}
