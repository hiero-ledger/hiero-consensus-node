// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.analyzer;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.processObjects;
import static com.swirlds.base.units.UnitConstants.BYTES_TO_MEBIBYTES;
import static java.math.RoundingMode.HALF_UP;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.StorageReport;
import com.hedera.statevalidation.validators.Utils.LongCountArray;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileIterator;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.streams.SerializableDataOutputStream;

public final class StateAnalyzer {

    private static final Logger log = LogManager.getLogger(StateAnalyzer.class);

    private StateAnalyzer() {}

    public static void analyzePathToKeyValueStorage(
            @NonNull final Report report, @NonNull final MerkleDbDataSource vds) {
        updateReport(
                report,
                new MemoryIndexDiskKeyValueStoreW<>(vds.getPathToKeyValue()).getFileCollection(),
                vds.getPathToDiskLocationLeafNodes().size(),
                Report::setPathToKeyValueReport,
                VirtualLeafBytes::parseFrom);
    }

    public static void analyzePathToHashStorage(@NonNull final Report report, @NonNull final MerkleDbDataSource vds) {
        updateReport(
                report,
                new MemoryIndexDiskKeyValueStoreW<>(vds.getHashStoreDisk()).getFileCollection(),
                vds.getPathToDiskLocationInternalNodes().size(),
                Report::setPathToHashReport,
                VirtualHashRecord::parseFrom);
    }

    private static void updateReport(
            @NonNull final Report report,
            @NonNull final DataFileCollection dataFileCollection,
            long indexSize,
            @NonNull final BiConsumer<Report, StorageReport> vmReportUpdater,
            @NonNull final Function<ReadableSequentialData, ?> deserializer) {
        final StorageReport storageReport = createStoreReport(dataFileCollection, indexSize, deserializer);
        final KeyRange validKeyRange = dataFileCollection.getValidKeyRange();
        storageReport.setMinPath(validKeyRange.getMinValidKey());
        storageReport.setMaxPath(validKeyRange.getMaxValidKey());
        vmReportUpdater.accept(report, storageReport);
    }

    private static StorageReport createStoreReport(
            @NonNull final DataFileCollection dfc,
            long indexSize,
            @NonNull final Function<ReadableSequentialData, ?> deserializer) {
        final LongCountArray itemCountByPath = new LongCountArray(indexSize);
        final List<DataFileReader> readers = dfc.getAllCompletedFiles();

        final AtomicLong duplicateItemCount = new AtomicLong();
        final AtomicLong failure = new AtomicLong();
        final AtomicLong itemCount = new AtomicLong();
        final AtomicLong fileCount = new AtomicLong();
        final AtomicLong sizeInMb = new AtomicLong();
        final AtomicLong wastedSpaceInBytes = new AtomicLong();

        Consumer<DataFileReader> dataFileProcessor = d -> {
            DataFileIterator dataIterator;
            fileCount.incrementAndGet();
            final double currentSizeInMb = d.getPath().toFile().length() * BYTES_TO_MEBIBYTES;
            sizeInMb.addAndGet((int) currentSizeInMb);
            final ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream(1024);
            try {
                dataIterator = d.createIterator();
                log.debug("Reading from file: {}", d.getIndex());

                while (dataIterator.next()) {
                    try {
                        int itemSize = 0;
                        final long path;
                        final Object dataItemData = deserializer.apply(dataIterator.getDataItemData());
                        if (dataItemData
                                instanceof @SuppressWarnings("DeconstructionCanBeUsed") VirtualHashRecord hashRecord) {
                            itemSize = hashRecord.hash().getSerializedLength() + /*path*/ Long.BYTES;
                            path = hashRecord.path();
                        } else if (dataItemData instanceof @SuppressWarnings("rawtypes") VirtualLeafBytes leafRecord) {
                            path = leafRecord.path();
                            final SerializableDataOutputStream outputStream =
                                    new SerializableDataOutputStream(arrayOutputStream);
                            outputStream.writeByteArray(leafRecord.keyBytes().toByteArray());
                            itemSize += outputStream.size();
                            arrayOutputStream.reset();
                            outputStream.writeByteArray(leafRecord.valueBytes().toByteArray());
                            itemSize += outputStream.size() + /*path*/ Long.BYTES;
                            arrayOutputStream.reset();
                        } else {
                            throw new UnsupportedOperationException("Unsupported data item type");
                        }

                        if (path >= indexSize) {
                            wastedSpaceInBytes.addAndGet(itemSize);
                        } else {
                            long oldVal = itemCountByPath.getAndIncrement(path);
                            if (oldVal > 0) {
                                wastedSpaceInBytes.addAndGet(itemSize);
                                if (oldVal == 1) {
                                    duplicateItemCount.incrementAndGet();
                                }
                            }
                        }
                    } catch (Exception e) {
                        failure.incrementAndGet();
                    } finally {
                        itemCount.incrementAndGet();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        processObjects(readers, dataFileProcessor).join();

        if (failure.get() != 0) {
            throw new IllegalStateException("Failure count should be 0");
        }

        log.debug("Leaves found in data files = {}, recreated LongList.size() = {}", itemCount, itemCountByPath.size());

        final StorageReport storageReport = new StorageReport();

        if (itemCount.get() > 0 && sizeInMb.get() > 0) {
            storageReport.setWastePercentage(
                    BigDecimal.valueOf(wastedSpaceInBytes.get() * BYTES_TO_MEBIBYTES * 100.0 / sizeInMb.get())
                            .setScale(3, HALF_UP)
                            .doubleValue());
        }

        storageReport.setDuplicateItems(duplicateItemCount.get());
        storageReport.setNumberOfStorageFiles(fileCount.get());
        storageReport.setOnDiskSizeInMb(sizeInMb.get());
        storageReport.setItemCount(itemCount.get());

        return storageReport;
    }
}
