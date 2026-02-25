// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.*;

import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataFileReaderTest {

    private final MerkleDbConfig dbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    private final DataFileMetadata dataFileMetadata = new DataFileMetadata(0, Instant.now(), 0);

    private File file;
    private DataFileReader dataFileReader;

    @BeforeEach
    void setUp() throws IOException {
        file = File.createTempFile("file-reader", "test");
        dataFileReader = new DataFileReader(dbConfig, file.toPath(), dataFileMetadata);
    }

    @AfterEach
    public void tearDown() throws IOException {
        dataFileReader.close();
        file.deleteOnExit();
    }

    @Test
    void testCloseIsIdempotent() throws IOException {
        dataFileReader.close();
        assertDoesNotThrow(() -> dataFileReader.close());
    }

    /**
     * Current algorithm for leasing file channels is to use a round-robin-like approach with one peculiarity to keep in mind:
     * it presumes that leases are going to be released shortely after they are taken. In this case, there will be even
     * distribution of leases among all available file channels. However, if a lease is not released, leases will be heavily
     * biased towards the first file channel, as this test demonstrates.
     */
    @Test
    void testLeaseFileChannel() throws IOException {
        for (int i = 0; i < dataFileReader.getThreadsPerFileChannel(); i++) {
            int lease = dataFileReader.leaseFileChannel();
            assertEquals(0, lease);
        }

        // opening new channel
        assertEquals(1, dataFileReader.leaseFileChannel());

        assertEquals(0, dataFileReader.leaseFileChannel());
        assertEquals(1, dataFileReader.leaseFileChannel());
        assertEquals(0, dataFileReader.leaseFileChannel());
        for (int i = 0; i < 5; i++) {
            dataFileReader.leaseFileChannel();
        }

        assertEquals(0, dataFileReader.leaseFileChannel());
        assertEquals(1, dataFileReader.leaseFileChannel());
        assertEquals(2, dataFileReader.leaseFileChannel());
        assertEquals(0, dataFileReader.leaseFileChannel());
        assertEquals(1, dataFileReader.leaseFileChannel());
        assertEquals(2, dataFileReader.leaseFileChannel());
    }

    @Test
    void testLeaseReleaseFileChannel() throws IOException {
        for (int i = 0; i < dataFileReader.getThreadsPerFileChannel(); i++) {
            int lease = dataFileReader.leaseFileChannel();
            assertEquals(0, lease);
        }

        // opening new channel
        assertEquals(1, dataFileReader.leaseFileChannel());

        dataFileReader.releaseFileChannel();
        dataFileReader.releaseFileChannel();
        dataFileReader.releaseFileChannel();

        assertEquals(1, dataFileReader.leaseFileChannel());
        assertEquals(0, dataFileReader.leaseFileChannel());
        assertEquals(1, dataFileReader.leaseFileChannel());
    }

    @Test
    void testLeaseFileChannel_maxFileChannels() throws IOException {
        for (int i = 0; i < dataFileReader.getThreadsPerFileChannel() * dataFileReader.getMaxFileChannels(); i++) {
            dataFileReader.leaseFileChannel();
        }

        // verifying that all channels were created
        assertEquals(
                dataFileReader.getMaxFileChannels(),
                dataFileReader.getFileChannelsCount(),
                "File channel count is unexpected");

        assertEquals(1, dataFileReader.leaseFileChannel());
        assertEquals(2, dataFileReader.leaseFileChannel());

        // verifying that no additional channels were created
        assertEquals(
                dataFileReader.getMaxFileChannels(),
                dataFileReader.getFileChannelsCount(),
                "File channel count is unexpected");
    }

    @Test
    void testLeaseFileChannel_leaseLeastUsed() throws IOException {
        for (int i = 0; i < dataFileReader.getThreadsPerFileChannel() * dataFileReader.getMaxFileChannels(); i++) {
            dataFileReader.leaseFileChannel();
        }

        dataFileReader.releaseFileChannel();

        assertEquals(0, dataFileReader.leaseFileChannel());
        assertEquals(1, dataFileReader.leaseFileChannel());
        assertEquals(2, dataFileReader.leaseFileChannel());
    }
}
