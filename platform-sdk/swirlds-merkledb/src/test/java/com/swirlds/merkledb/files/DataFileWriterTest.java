// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.FIELD_DATAFILE_ITEMS;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.randomUtf8Bytes;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.ProtoWriterTools;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataFileWriterTest {

    private static final int BUFFER_SIZE = 1024;

    private DataFileWriter dataFileWriter;

    @BeforeEach
    public void setUp() throws Exception {
        Path dataFileWriterPath = Files.createTempDirectory("dataFileWriter");
        dataFileWriter = new DataFileWriter("test", dataFileWriterPath, 1, Instant.now(), 1, BUFFER_SIZE);
    }

    @Test
    public void writeAfterCloseIsNotAllowed() throws IOException {
        BufferedData data = BufferedData.wrap("test".getBytes());

        dataFileWriter.storeDataItem(data);
        dataFileWriter.close();

        data.flip();
        assertThrows(
                IOException.class, () -> dataFileWriter.storeDataItem(data), "Cannot write after writing is finished");
    }

    @Test
    public void closeWriterIsIdempotent() throws IOException {
        BufferedData data = BufferedData.wrap("test".getBytes());

        dataFileWriter.storeDataItem(data);
        dataFileWriter.close();
        assertDoesNotThrow(() -> dataFileWriter.close(), "Closing writer should be idempotent");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    public void wrongEstimatedSizeWrite(int diff) {
        BufferedData data = BufferedData.wrap("test".getBytes());

        assertThrows(
                IOException.class,
                () -> dataFileWriter.storeDataItem(o -> o.writeBytes(data), (int) (data.length() + diff)),
                "Wrong estimated data size");
    }

    @Test
    public void smallBufferBigDataItem() {
        BufferedData data = BufferedData.wrap(randomUtf8Bytes(BUFFER_SIZE - 2));
        assertThrows(IOException.class, () -> dataFileWriter.storeDataItem(data), "Buffer is too small to write data");
    }

    @Test
    public void manySmallWritesBufferIsMoving() throws IOException {
        int dataLengthBytes = (int) (BUFFER_SIZE * 0.01);
        int iterations = 1000;
        BufferedData data = BufferedData.wrap(randomUtf8Bytes(dataLengthBytes));

        for (int i = 0; i < iterations; i++) {
            dataFileWriter.storeDataItem(data);
            data.flip();
        }

        dataFileWriter.close();
        verifyFileSize(dataLengthBytes * iterations);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 57, BUFFER_SIZE / 2, BUFFER_SIZE - 3})
    public void correctFileSizeAfterFinishWriting(int dataLengthBytes) throws IOException {
        byte[] bytesData = randomUtf8Bytes(dataLengthBytes);
        BufferedData data = BufferedData.wrap(bytesData);

        dataFileWriter.storeDataItem(data);
        dataFileWriter.close();

        verifyFileSize(bytesData.length);
    }

    private void verifyFileSize(int dataLength) throws IOException {
        int fileSize = (int) Files.size(dataFileWriter.getPath());
        int dataSize = ProtoWriterTools.sizeOfDelimited(FIELD_DATAFILE_ITEMS, dataLength);
        int headerSize = dataFileWriter.getMetadata().metadataSizeInBytes();

        assertEquals(headerSize, dataSize, fileSize, "Unexpected file size");
    }
}
