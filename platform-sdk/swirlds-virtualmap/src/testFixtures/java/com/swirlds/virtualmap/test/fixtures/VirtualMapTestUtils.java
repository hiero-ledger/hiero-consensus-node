// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * Methods for testing {@link VirtualMap}.
 */
public final class VirtualMapTestUtils {

    private VirtualMapTestUtils() {}

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .withConfigDataType(ReconnectConfig.class)
            .build();

    public static final VirtualMapConfig VIRTUAL_MAP_CONFIG = CONFIGURATION.getConfigData(VirtualMapConfig.class);

    public static VirtualMap createMap() {
        final VirtualDataSourceBuilder builder = new InMemoryBuilder();
        return new VirtualMap(builder, CONFIGURATION);
    }

    /**
     * Validate that two virtual maps contain the same data.
     */
    public static void assertVmsAreEqual(final VirtualMap originalMap, final VirtualMap deserializedMap) {
        assertEquals(originalMap.size(), deserializedMap.size(), "size should match");

        if (originalMap.isEmpty() && deserializedMap.isEmpty()) {
            return;
        }

        // make sure that the hashes are calculated
        originalMap.getHash();
        deserializedMap.getHash();

        assertEquals(originalMap.getHash(), deserializedMap.getHash(), "hash should match");

        final VirtualMapMetadata originalMapMetadata = originalMap.getMetadata();
        final VirtualMapMetadata deserializedMapMetadata = deserializedMap.getMetadata();

        assertEquals(originalMapMetadata, deserializedMapMetadata, "metadata should match");

        for (long i = originalMapMetadata.getFirstLeafPath(); i <= originalMapMetadata.getLastLeafPath(); i++) {
            assertEquals(
                    originalMap.getRecords().findLeafRecord(i),
                    deserializedMap.getRecords().findLeafRecord(i),
                    "leaf records should match");
        }

        for (long i = 0; i <= originalMapMetadata.getLastLeafPath(); i++) {
            assertEquals(
                    originalMap.getRecords().findHash(i),
                    deserializedMap.getRecords().findHash(i),
                    "hashes should match");
        }
    }
}
