// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;

import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MerkleDbTableConfigTest {

    @BeforeAll
    public static void setup() throws Exception {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.merkledb");
    }

    @Test
    void deserializeDefaultsTest() throws IOException {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final MerkleDbTableConfig tableConfig = new MerkleDbTableConfig(
                (short) 1,
                DigestType.SHA_384,
                merkleDbConfig.maxNumOfKeys(),
                merkleDbConfig.hashesRamToDiskThreshold());

        Assertions.assertEquals(merkleDbConfig.maxNumOfKeys(), tableConfig.getMaxNumberOfKeys());
        Assertions.assertEquals(merkleDbConfig.hashesRamToDiskThreshold(), tableConfig.getHashesRamToDiskThreshold());

        Assertions.assertThrows(IllegalArgumentException.class, () -> tableConfig.maxNumberOfKeys(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> tableConfig.maxNumberOfKeys(-1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> tableConfig.hashesRamToDiskThreshold(-1));

        // Default protobuf value, will not be serialized
        tableConfig.hashesRamToDiskThreshold(0);

        final ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try (final WritableStreamingData out = new WritableStreamingData(bout)) {
            tableConfig.writeTo(out);
        }

        final byte[] arr = bout.toByteArray();
        final MerkleDbTableConfig restored;
        try (final ReadableStreamingData in = new ReadableStreamingData(arr)) {
            restored = new MerkleDbTableConfig(in);
        }

        Assertions.assertEquals(merkleDbConfig.maxNumOfKeys(), restored.getMaxNumberOfKeys());
        // Fields that aren't deserialized should have default protobuf values (e.g. zero), not
        // default MerkleDbConfig values
        Assertions.assertEquals(0, restored.getHashesRamToDiskThreshold());
    }
}
