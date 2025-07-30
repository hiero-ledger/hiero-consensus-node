// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.processObjects;
import static com.swirlds.base.units.UnitConstants.BYTES_TO_MEBIBYTES;
import static java.lang.Math.toIntExact;
import static java.math.RoundingMode.HALF_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.hedera.statevalidation.reporting.StorageReport;
import com.hedera.statevalidation.reporting.VirtualMapReport;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileIterator;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("stateAnalyzer")
public class StateAnalyzer {

    private static final Logger log = LogManager.getLogger(StateAnalyzer.class);

    @Test
    void validateDuplicatesForPathToKeyValueStorage(DeserializedSignedState deserializedState, Report report) {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);
        MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

        updateReport(
                virtualMap.getLabel(),
                report,
                new MemoryIndexDiskKeyValueStoreW<>(vds.getPathToKeyValue()).getFileCollection(),
                vds.getPathToDiskLocationLeafNodes().size(),
                VirtualMapReport::setPathToKeyValueReport,
                VirtualLeafBytes::parseFrom);
        System.out.println("[Report] Duplicates for path to key value storage:\n" + report);
    }

    @Test
    void validateDuplicatesForPathToHashStorage(DeserializedSignedState deserializedState, Report report) {
        final MerkleNodeState merkleNodeState =
                deserializedState.reservedSignedState().get().getState();
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        assertNotNull(virtualMap);
        MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

        updateReport(
                virtualMap.getLabel(),
                report,
                new MemoryIndexDiskKeyValueStoreW<>(vds.getHashStoreDisk()).getFileCollection(),
                vds.getPathToDiskLocationInternalNodes().size(),
                VirtualMapReport::setPathToHashReport,
                VirtualHashRecord::parseFrom);
        System.out.println("[Report] Duplicates for path to hash storage:\n" + report);
    }

    private void updateReport(
            String name,
            Report report,
            DataFileCollection dataFileCollection,
            long indexSize,
            BiConsumer<VirtualMapReport, StorageReport> vmReportUpdater,
            Function<ReadableSequentialData, ?> deser) {
        VirtualMapReport vmReport = report.getVmapReportByName().computeIfAbsent(name, k -> new VirtualMapReport());
        StorageReport storageReport = createStoreReport(dataFileCollection, indexSize, deser);
        KeyRange validKeyRange = dataFileCollection.getValidKeyRange();
        storageReport.setMinPath(validKeyRange.getMinValidKey());
        storageReport.setMaxPath(validKeyRange.getMaxValidKey());
        vmReportUpdater.accept(vmReport, storageReport);
    }

    private static StorageReport createStoreReport(
            DataFileCollection dfc, long indexSize, Function<ReadableSequentialData, ?> deser) {
        LongCountArray itemCountByPath = new LongCountArray(indexSize);
        List<DataFileReader> readers = dfc.getAllCompletedFiles();

        AtomicLong duplicateItemCount = new AtomicLong();
        AtomicLong failure = new AtomicLong();
        AtomicLong itemCount = new AtomicLong();
        AtomicLong fileCount = new AtomicLong();
        AtomicLong sizeInMb = new AtomicLong();
        AtomicLong wastedSpaceInBytes = new AtomicLong();

        Consumer<DataFileReader> dataFileProcessor = d -> {
            DataFileIterator dataIterator;
            fileCount.incrementAndGet();
            double currentSizeInMb = d.getPath().toFile().length() * BYTES_TO_MEBIBYTES;
            sizeInMb.addAndGet((int) currentSizeInMb);
            ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream(1024);
            try {
                dataIterator = d.createIterator();
                log.info("Reading from file: {}", d.getIndex());

                while (dataIterator.next()) {
                    try {
                        // int itemSize = dataIterator.getDataItemData().remaining();
                        int itemSize = 0;
                        final long path;
                        Object dataItemData = deser.apply(dataIterator.getDataItemData());
                        if (dataItemData instanceof VirtualHashRecord hashRecord) {
                            itemSize = hashRecord.hash().getSerializedLength() + /*path*/ Long.BYTES;
                            path = hashRecord.path();
                        } else if (dataItemData instanceof VirtualLeafBytes leafRecord) {
                            path = leafRecord.path();
                            SerializableDataOutputStream outputStream =
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

                        long oldVal = itemCountByPath.getAndIncrement(path);
                        if (oldVal > 0) {
                            wastedSpaceInBytes.addAndGet(itemSize);
                            if (oldVal == 1) {
                                duplicateItemCount.incrementAndGet();
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

        assertEquals(0, failure.get(), "Failure count should be 0");

        log.info("Leaves found in data files = {}, recreated LongList.size() = {}", itemCount, itemCountByPath.size());
        StorageReport storageReport = new StorageReport();
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

    static class LongCountArray {

        static final int LONGS_PER_CHUNK = 1 << 20;
        final long size;
        AtomicLongArray[] arrays;

        LongCountArray(long size) {
            this.size = size;
            int maxChunkIndex = toIntExact((size - 1) / LONGS_PER_CHUNK);
            arrays = new AtomicLongArray[maxChunkIndex + 1];
            for (int i = 0; i < arrays.length; ++i) {
                arrays[i] = new AtomicLongArray(LONGS_PER_CHUNK);
            }
        }

        long size() {
            return size;
        }

        long getAndIncrement(long idx) {
            if (idx < 0 || idx >= size) {
                throw new IndexOutOfBoundsException();
            }
            int chunkIdx = toIntExact(idx / LONGS_PER_CHUNK);
            return arrays[chunkIdx].getAndIncrement(toIntExact(idx % LONGS_PER_CHUNK));
        }
    }
}
