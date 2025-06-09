// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import java.util.function.LongFunction;
import java.util.stream.LongStream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Test a *HUGE* billion-leaf tree. This test is separated out from {@link VirtualHasherTest} so we can
 * easily ignore it or include it depending on the build context. It takes several minutes to complete.
 */
class VirtualHasherHugeTest extends VirtualHasherTestBase {
    private static final long NUM_LEAVES = 1_000_000_000;

    /**
     * Test a huge billion-leaf tree. This test takes a significant amount of time.
     */
    @Test
    @Tag(TestComponentTags.VMAP)
    @DisplayName("Test a massive tree where all leaves are dirty")
    @Disabled
    void hugeTree() {
        final long firstLeafPath = NUM_LEAVES - 1;
        final long lastLeafPath = (NUM_LEAVES * 2) - 2;

        // Every leaf has a null hash for this test, and we never ask for the same leaf twice, so we
        // can have a simple function for creating virtual leaf records.
        final LongFunction<VirtualLeafRecord<TestKey, TestValue>> leafGetter =
                (path) -> new VirtualLeafRecord<>(path, new TestKey(path), new TestValue("" + path));

        // Since we're hashing every internal node, we can generate new ones with null hashes. None are ever
        // asked for twice.
        final LongFunction<VirtualHashRecord> internalGetter = VirtualHashRecord::new;

        // Go ahead and hash. I'm just going to check that the root hash produces *something*. I'm not worried
        // in this test as to the validity of this root hash since correctness is validated heavily in other
        // tests. In this test, I just want to be sure that we complete, and that we don't run out of memory.
        final VirtualHasher<TestKey, TestValue> hasher = new VirtualHasher<>();
        final Hash rootHash = hasher.hash(
                path -> null,
                LongStream.range(firstLeafPath, lastLeafPath + 1)
                        .mapToObj(leafGetter)
                        .iterator(),
                firstLeafPath,
                lastLeafPath,
                null,
                CONFIGURATION.getConfigData(VirtualMapConfig.class));
        assertNotNull(rootHash, "No hash produced");
    }
}
