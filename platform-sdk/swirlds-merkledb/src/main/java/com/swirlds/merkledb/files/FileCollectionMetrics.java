// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.LABEL_COMPACTION_LEVEL;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.LABEL_STORE_NAME;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FILES_CNT;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FILES_GARBAGE_RATIO;
import static com.swirlds.merkledb.MerkleDbMetricsRegistrationProvider.METRIC_KEY_FILES_SIZE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.LongSummaryStatistics;
import org.hiero.metrics.DoubleAccumulatorGauge;
import org.hiero.metrics.LongAccumulatorGauge;
import org.hiero.metrics.core.MetricRegistry;

public final class FileCollectionMetrics {

    private final DataFileCollection dataFileCollection;
    private final LongAccumulatorGauge filesCount;
    private final LongAccumulatorGauge filesSize;
    private final DoubleAccumulatorGauge garbageRatio;

    public FileCollectionMetrics(@NonNull DataFileCollection dataFileCollection, @NonNull MetricRegistry registry) {
        this.dataFileCollection = dataFileCollection;
        filesCount = registry.getMetric(METRIC_KEY_FILES_CNT);
        filesSize = registry.getMetric(METRIC_KEY_FILES_SIZE);
        garbageRatio = registry.getMetric(METRIC_KEY_FILES_GARBAGE_RATIO);
    }

    public void updateFileMetricsOnNewFile(DataFileReader reader) {
        if (reader.isFileCompleted()) {
            final String storeName = dataFileCollection.getStoreName();
            final String level = String.valueOf(reader.getMetadata().getCompactionLevel());
            filesCount
                    .getOrCreateLabeled(LABEL_STORE_NAME, storeName, LABEL_COMPACTION_LEVEL, level)
                    .accumulate(1L);
            filesSize
                    .getOrCreateLabeled(LABEL_STORE_NAME, storeName, LABEL_COMPACTION_LEVEL, level)
                    .accumulate(reader.getSize());
        }
    }

    public void updateFileMetricsOnDeletedFiles(List<DataFileReader> deletedFiles) {
        LongSummaryStatistics stats = deletedFiles.stream()
                .filter(DataFileReader::isFileCompleted)
                .mapToLong(DataFileReader::getSize)
                .summaryStatistics();

        if (stats.getCount() > 0) {
            final String storeName = dataFileCollection.getStoreName();
            final String level =
                    String.valueOf(deletedFiles.getFirst().getMetadata().getCompactionLevel());

            filesCount
                    .getOrCreateLabeled(LABEL_STORE_NAME, storeName, LABEL_COMPACTION_LEVEL, level)
                    .accumulate(-stats.getCount());
            filesSize
                    .getOrCreateLabeled(LABEL_STORE_NAME, storeName, LABEL_COMPACTION_LEVEL, level)
                    .accumulate(-stats.getSum());
        }
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
