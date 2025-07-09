// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.FILE_EXTENSION;
import static com.swirlds.merkledb.files.DataFileCommon.createDataFilePath;
import static com.swirlds.merkledb.files.DataFileCompactor.INITIAL_COMPACTION_LEVEL;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.files.FilesTestType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings("SameParameterValue")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataFileLowLevelTest {

    /** Temporary directory provided by JUnit */
    @SuppressWarnings("unused")
    @TempDir
    static Path tempFileDir;

    private final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    private static final Random RANDOM = new Random(123456);
    private static final Instant TEST_START = Instant.now();
    private static final int DATA_FILE_INDEX = 123;
    private static final int ITEMS_SIZE = 1000;

    private static final Map<FilesTestType, DataFileMetadata> dataFileMetadataMap = new HashMap<>();
    private static final Map<FilesTestType, Path> dataFileMap = new HashMap<>();
    private static final Map<FilesTestType, LongArrayList> listOfDataItemLocationsMap = new HashMap<>();

    // =================================================================================================================
    // Helper Methods

    /**
     * For tests, we want to have all different data sizes, so we use this function to choose how
     * many times to repeat the data value long
     */
    private int getRepeatCountForKey(long key) {
        return (int) (key % 20L);
    }

    /** Create an example variable sized data item with lengths of data from 0 to 20. */
    private long[] getVariableSizeDataForI(int i) {
        int repeatCount = getRepeatCountForKey(i);
        long[] dataValue = new long[1 + repeatCount];
        dataValue[0] = i;
        for (int j = 1; j < dataValue.length; j++) {
            dataValue[j] = i + 10_000;
        }
        return dataValue;
    }

    private static long storeDataItem(final DataFileWriter writer, final long[] data) throws IOException {
        return writer.storeDataItem(
                o -> {
                    o.writeLong(data.length);
                    for (long l : data) {
                        o.writeLong(l);
                    }
                },
                Long.BYTES + data.length * Long.BYTES);
    }

    private static long[] readDataItem(final DataFileReader reader, final long location) throws IOException {
        final BufferedData data = reader.readDataItem(location);
        if (data == null) {
            return null;
        }
        final long[] item = new long[Math.toIntExact(data.readLong())];
        for (int i = 0; i < item.length; i++) {
            item[i] = data.readLong();
        }
        return item;
    }

    /** Check a fixed or variable size data items data */
    private void checkItem(FilesTestType testType, int i, long[] dataItem) {
        switch (testType) {
            default:
            case fixed:
                assertEquals(i, dataItem[0], "unexpected data item[0]");
                assertEquals(i + 10_000, dataItem[1], "unexpected data item[1]");
                break;
            case variable:
                assertEquals(
                        Arrays.toString(getVariableSizeDataForI(i)),
                        Arrays.toString(dataItem),
                        "unexpected variable data");
                break;
        }
    }

    // =================================================================================================================
    // Tests

    @Order(2)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void createFile(FilesTestType testType) throws IOException {
        // open file and write data
        DataFileWriter writer = new DataFileWriter(
                "test_" + testType.name(), tempFileDir, DATA_FILE_INDEX, TEST_START, INITIAL_COMPACTION_LEVEL);
        LongArrayList listOfDataItemLocations = new LongArrayList(ITEMS_SIZE);
        for (int i = 0; i < ITEMS_SIZE; i++) {
            long[] dataValue;
            switch (testType) {
                default:
                case fixed:
                    dataValue = new long[] {i, i + 10_000};
                    break;
                case variable:
                    dataValue = getVariableSizeDataForI(i);
                    break;
            }

            listOfDataItemLocations.add(storeDataItem(writer, dataValue));
        }
        writer.close();
        final DataFileMetadata dataFileMetadata = writer.getMetadata();
        // tests
        assertTrue(Files.exists(writer.getPath()), "expected file does not exist");
        assertEquals(
                writer.getPath(),
                createDataFilePath("test_" + testType.name(), tempFileDir, DATA_FILE_INDEX, TEST_START, FILE_EXTENSION),
                "unexpected path for writer");
        // store for later tests
        dataFileMap.put(testType, writer.getPath());
        dataFileMetadataMap.put(testType, dataFileMetadata);
        listOfDataItemLocationsMap.put(testType, listOfDataItemLocations);
    }

    @Order(100)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void checkMetadataOfWrittenFile(FilesTestType testType) {
        final DataFileMetadata dataFileMetadata = dataFileMetadataMap.get(testType);
        // check metadata
        assertEquals(TEST_START, dataFileMetadata.getCreationDate(), "unexpected creation date");
        assertEquals(DATA_FILE_INDEX, dataFileMetadata.getIndex(), "unexpected Index");
        assertEquals(INITIAL_COMPACTION_LEVEL, dataFileMetadata.getCompactionLevel(), "unexpected compaction level");
    }

    @Order(101)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void checkMetadataOfWrittenFileReadBack(FilesTestType testType) throws IOException {
        final DataFileMetadata dataFileMetadata = DataFileMetadata.readFromFile(dataFileMap.get(testType));
        // check metadata
        assertEquals(TEST_START, dataFileMetadata.getCreationDate(), "unexpected creation date");
        assertEquals(DATA_FILE_INDEX, dataFileMetadata.getIndex(), "unexpected Index value");
        assertEquals(INITIAL_COMPACTION_LEVEL, dataFileMetadata.getCompactionLevel(), "unexpected compaction level");
    }

    @Order(200)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void readBackRawData(FilesTestType testType) throws IOException {
        final Path dataFile = dataFileMap.get(testType);
        final int fileSize = (int) Files.size(dataFile);
        final DataFileMetadata dataFileMetadata = dataFileMetadataMap.get(testType);
        final int headerSize = dataFileMetadata.metadataSizeInBytes();
        // read the whole file
        BufferedData buf = BufferedData.wrap(Files.readAllBytes(dataFile), headerSize, fileSize - headerSize);

        for (int i = 0; i < ITEMS_SIZE; i++) {
            final int tag = buf.readVarInt(false);
            assertEquals(DataFileCommon.FIELD_DATAFILE_ITEMS.number(), tag >>> ProtoParserTools.TAG_FIELD_OFFSET);
            final int size = buf.readVarInt(false);
            switch (testType) {
                case fixed:
                    {
                        assertEquals(Long.BYTES + Long.BYTES * 2, size); // size + data
                        int dataItemSize = (int) buf.readLong();
                        assertEquals(2, dataItemSize); // data length
                        assertEquals(i, buf.readLong(), "unexpected data value");
                        assertEquals(i + 10_000, buf.readLong(), "unexpected value from get()");
                    }
                    break;
                case variable:
                    {
                        int repeatCount = getRepeatCountForKey(i);
                        assertEquals(Long.BYTES + Long.BYTES + Long.BYTES * repeatCount, size); // size + key + data
                        int dataItemSize = (int) buf.readLong();
                        assertEquals(1 + repeatCount, dataItemSize); // key + data length
                        // read key
                        assertEquals(i, buf.readLong(), "unexpected data value #2");
                        for (int j = 0; j < repeatCount; j++) {
                            assertEquals(i + 10_000, buf.readLong(), "unexpected value from get() #2");
                        }
                    }
                    break;
                default:
                    buf.skip(size);
            }
        }

        assertEquals(0, buf.remaining(), "buffer is not fully read");
    }

    @Order(201)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void readBackWithReader(FilesTestType testType) throws IOException {
        final Path dataFile = dataFileMap.get(testType);
        final DataFileMetadata dataFileMetadata = dataFileMetadataMap.get(testType);
        final LongArrayList listOfDataItemLocations = listOfDataItemLocationsMap.get(testType);
        DataFileReader dataFileReader = new DataFileReader(dbConfig, dataFile, dataFileMetadata);

        // check by locations returned by write
        for (int i = 0; i < ITEMS_SIZE; i++) {
            long[] dataItem = readDataItem(dataFileReader, listOfDataItemLocations.get(i));
            checkItem(testType, i, dataItem);
        }
        // check by location math
        if (testType == FilesTestType.fixed) {
            long offset = dataFileMetadata.metadataSizeInBytes();
            for (int i = 0; i < ITEMS_SIZE; i++) {
                long[] dataItem = readDataItem(dataFileReader, DataFileCommon.dataLocation(DATA_FILE_INDEX, offset));
                assertEquals(i, dataItem[0], "unexpected dataItem[0]");
                assertEquals(i + 10_000, dataItem[1], "unexpected dataItem[1]");
                offset += ProtoWriterTools.sizeOfDelimited(
                        DataFileCommon.FIELD_DATAFILE_ITEMS, Long.BYTES + Long.BYTES * 2);
            }
        }
        // check by random
        IntStream.range(0, 10_000).map(i -> RANDOM.nextInt(ITEMS_SIZE)).forEach(i -> {
            try {
                long[] dataItem = readDataItem(dataFileReader, listOfDataItemLocations.get(i));
                checkItem(testType, i, dataItem);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        // check by random parallel
        IntStream.range(0, 10_000)
                .map(i -> RANDOM.nextInt(ITEMS_SIZE))
                .parallel()
                .forEach(i -> {
                    try {
                        long[] dataItem = readDataItem(dataFileReader, listOfDataItemLocations.get(i));
                        checkItem(testType, i, dataItem);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        // some additional asserts to increase DataFileReader's coverage.
        DataFileReader secondReader = new DataFileReader(dbConfig, dataFile);
        DataFileIterator firstIterator = dataFileReader.createIterator();
        DataFileIterator secondIterator = secondReader.createIterator();
        assertEquals(firstIterator.getMetadata(), secondIterator.getMetadata(), "unexpected metadata");
        assertEquals(
                firstIterator.getMetadata().hashCode(),
                secondIterator.getMetadata().hashCode(),
                "unexpected hashcode");
        assertEquals(firstIterator.toString(), secondIterator.toString(), "unexpected toString() value #1");
        assertEquals(firstIterator.hashCode(), secondIterator.hashCode(), "unexpected hashCode() value #1");
        assertEquals(firstIterator, secondIterator, "unexpected iterator value");
        assertEquals(secondReader.getIndex(), dataFileReader.getIndex(), "unexpected Index value");
        assertEquals(secondReader.getPath(), dataFileReader.getPath(), "unexpected Path number");
        assertEquals(secondReader.getSize(), dataFileReader.getSize(), "unexpected reader size");
        assertEquals(secondReader, dataFileReader, "unexpected reader value");
        assertEquals(secondReader.hashCode(), dataFileReader.hashCode(), "unexpected hashCode() value #2");
        assertEquals(0, secondReader.compareTo(dataFileReader), "readers don't compare as expected");
        assertEquals(secondReader.toString(), dataFileReader.toString(), "unexpected toString() value #2");

        firstIterator.close();
        secondIterator.close();
        dataFileReader.close();
        secondReader.close();
    }

    @Order(300)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void readBackWithIterator(FilesTestType testType) throws IOException {
        final Path dataFile = dataFileMap.get(testType);
        final DataFileMetadata dataFileMetadata = dataFileMetadataMap.get(testType);
        final LongArrayList listOfDataItemLocations = listOfDataItemLocationsMap.get(testType);

        int i = 0;
        try (DataFileIterator fileIterator = new DataFileIterator(dbConfig, dataFile, dataFileMetadata)) {
            while (fileIterator.next()) {
                assertEquals(
                        listOfDataItemLocations.get(i),
                        fileIterator.getDataItemDataLocation(),
                        "unexpected data items data location");
                BufferedData dataItemData = fileIterator.getDataItemData();
                assertEquals(
                        i, dataItemData.getLong(dataItemData.position() + Long.BYTES), "unexpected data items key");
                final long[] dataItem = new long[Math.toIntExact(dataItemData.readLong())];
                for (int j = 0; j < dataItem.length; j++) {
                    dataItem[j] = dataItemData.readLong();
                }
                checkItem(testType, i, dataItem);
                i++;
            }
        }

        assertEquals(ITEMS_SIZE, i, "unexpected number of items read");
    }

    @Order(400)
    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void copyFile(FilesTestType testType) throws IOException {
        DataFileWriter newDataFileWriter = new DataFileWriter(
                "test_" + testType.name(),
                tempFileDir,
                DATA_FILE_INDEX + 1,
                TEST_START.plus(1, ChronoUnit.SECONDS),
                INITIAL_COMPACTION_LEVEL);

        final Path dataFile = dataFileMap.get(testType);
        final DataFileMetadata dataFileMetadata = dataFileMetadataMap.get(testType);
        final LongArrayList newDataLocations;

        try (DataFileIterator fileIterator = new DataFileIterator(dbConfig, dataFile, dataFileMetadata)) {
            newDataLocations = new LongArrayList(ITEMS_SIZE);
            while (fileIterator.next()) {
                final BufferedData itemData = fileIterator.getDataItemData();
                newDataLocations.add(newDataFileWriter.storeDataItem(itemData));
            }
        }

        newDataFileWriter.close();
        final DataFileMetadata newDataFileMetadata = newDataFileWriter.getMetadata();
        // now read back and check
        DataFileReader dataFileReader = new DataFileReader(dbConfig, newDataFileWriter.getPath(), newDataFileMetadata);
        // check by locations returned by write
        for (int i = 0; i < ITEMS_SIZE; i++) {
            long[] dataItem = readDataItem(dataFileReader, newDataLocations.get(i));
            checkItem(testType, i, dataItem);
        }
    }
}
