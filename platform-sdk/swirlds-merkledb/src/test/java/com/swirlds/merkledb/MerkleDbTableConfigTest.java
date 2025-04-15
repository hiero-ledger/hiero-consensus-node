// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.consensus.model.crypto.DigestType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MerkleDbTableConfigTest {

    @BeforeAll
    public static void setup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.merkledb");
    }

    @Test
    void testIllegalMaxNumOfKeys() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MerkleDbTableConfig(
                        (short) 1, DigestType.SHA_384, 0, merkleDbConfig.hashesRamToDiskThreshold()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MerkleDbTableConfig(
                        (short) 1, DigestType.SHA_384, -1, merkleDbConfig.hashesRamToDiskThreshold()));
    }

    @Test
    void testIllegalHashesRamToDiskThreshold() {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MerkleDbTableConfig((short) 1, DigestType.SHA_384, merkleDbConfig.maxNumOfKeys(), -1));
    }

    @Test
    void deserializeDefaultsTest() throws IOException {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final MerkleDbTableConfig tableConfig = new MerkleDbTableConfig(
                (short) 1, DigestType.SHA_384, 1_000, 0); // Default protobuf value, will not be serialized

        Assertions.assertEquals(1_000, tableConfig.getMaxNumberOfKeys());
        Assertions.assertEquals(0, tableConfig.getHashesRamToDiskThreshold());

        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (final WritableStreamingData out = new WritableStreamingData(bout)) {
            tableConfig.writeTo(out);
        }

        final byte[] arr = bout.toByteArray();
        final MerkleDbTableConfig restored;
        try (final ReadableStreamingData in = new ReadableStreamingData(arr)) {
            restored = new MerkleDbTableConfig(in);
        }

        Assertions.assertEquals(1_000, restored.getMaxNumberOfKeys());
        // Fields that aren't deserialized should have default protobuf values (e.g. zero), not
        // default MerkleDbConfig values
        Assertions.assertEquals(0, restored.getHashesRamToDiskThreshold());
    }
}
