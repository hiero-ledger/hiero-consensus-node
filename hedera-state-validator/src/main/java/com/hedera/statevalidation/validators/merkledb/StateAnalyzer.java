// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.merkledb;

import static com.hedera.statevalidation.parameterresolver.InitUtils.CONFIGURATION;
import static com.hedera.statevalidation.validators.ParallelProcessingUtil.processObjects;
import static com.swirlds.base.units.UnitConstants.BYTES_TO_MEBIBYTES;
import static java.math.RoundingMode.HALF_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.VirtualMapAndDataSourceProvider;
import com.hedera.statevalidation.parameterresolver.VirtualMapAndDataSourceRecord;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.hedera.statevalidation.reporting.StorageReport;
import com.hedera.statevalidation.reporting.VirtualMapReport;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileIterator;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

@ExtendWith({ReportResolver.class, SlackReportGenerator.class})
@Tag("stateAnalyzer")
public class StateAnalyzer {

    private static final Logger log = LogManager.getLogger(StateAnalyzer.class);

    @ParameterizedTest
    @ArgumentsSource(VirtualMapAndDataSourceProvider.class)
    public void calculateDuplicatesForPathToKeyValueStorage(VirtualMapAndDataSourceRecord labelAndDs, Report report) {
        MerkleDbDataSource vds = labelAndDs.dataSource();
        updateReport(
                labelAndDs,
                report,
                new MemoryIndexDiskKeyValueStoreW<>(vds.getPathToKeyValue()).getFileCollection(),
                VirtualMapReport::setPathToKeyValueReport,
                VirtualLeafBytes::parseFrom);
        System.out.println("[Report] Duplicates for path to key value storage:\n" + report);
    }

    @ParameterizedTest
    @ArgumentsSource(VirtualMapAndDataSourceProvider.class)
    public void calculateDuplicatesForPathToHashStorage(VirtualMapAndDataSourceRecord labelAndDs, Report report) {
        MerkleDbDataSource vds = labelAndDs.dataSource();
        updateReport(
                labelAndDs,
                report,
                new MemoryIndexDiskKeyValueStoreW<>(vds.getHashStoreDisk()).getFileCollection(),
                VirtualMapReport::setPathToHashReport,
                VirtualHashRecord::parseFrom);
        System.out.println("[Report] Duplicates for path to hash storage:\n" + report);
    }

    @ParameterizedTest
    @ArgumentsSource(VirtualMapAndDataSourceProvider.class)
    public void calculateDuplicatesForObjectKeyToPathStorage(VirtualMapAndDataSourceRecord labelAndDs, Report report) {
        //          MerkleDbDataSource vds = labelAndDs.dataSource();
        //          updateReport(labelAndDs, report, new HalfDiskHashMapW(vds.getKeyToPath()).getFileCollection(),
        // VirtualMapReport::setObjectKeyToPathReport);
    }

    private void updateReport(
            VirtualMapAndDataSourceRecord labelAndDs,
            Report report,
            DataFileCollection dataFileCollection,
            BiConsumer<VirtualMapReport, StorageReport> vmReportUpdater,
            Function<ReadableSequentialData, ?> deser) {
        VirtualMapReport vmReport =
                report.getVmapReportByName().computeIfAbsent(labelAndDs.name(), k -> new VirtualMapReport());
        StorageReport storageReport = createStoreReport(dataFileCollection, deser);
        KeyRange validKeyRange = dataFileCollection.getValidKeyRange();
        storageReport.setMinPath(validKeyRange.getMinValidKey());
        storageReport.setMaxPath(validKeyRange.getMaxValidKey());
        vmReportUpdater.accept(vmReport, storageReport);
    }

    private static StorageReport createStoreReport(DataFileCollection dfc, Function<ReadableSequentialData, ?> deser) {
        MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        int goodAverageBucketEntryCount = merkleDbConfig.goodAverageBucketEntryCount();
        long bucketIndexCapacity = merkleDbConfig.maxNumOfKeys() * 2 / goodAverageBucketEntryCount;

        LongList itemCountByPath = new LongListHeap(bucketIndexCapacity, CONFIGURATION);
        List<DataFileReader> readers = dfc.getAllCompletedFiles();

        AtomicInteger duplicateItemCount = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicInteger itemCount = new AtomicInteger();
        AtomicInteger fileCount = new AtomicInteger();
        AtomicInteger sizeInMb = new AtomicInteger();
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
                        long oldVal = itemCountByPath.get(path);
                        if (oldVal > 0) {
                            itemCountByPath.put(path, oldVal + 1);
                            wastedSpaceInBytes.addAndGet(itemSize);
                            if (oldVal == 1) {
                                duplicateItemCount.incrementAndGet();
                            }
                        } else {
                            itemCountByPath.put(path, 1);
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
}
