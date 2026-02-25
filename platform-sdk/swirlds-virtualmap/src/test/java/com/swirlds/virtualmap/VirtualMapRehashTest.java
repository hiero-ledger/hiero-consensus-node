// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.hash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VirtualMapRehashTest extends VirtualTestBase {

    private InMemoryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new InMemoryBuilder();
    }

    @Test
    @DisplayName("Test rehash is skipped if map is empty")
    void testRehashSkippedIfEmpty() {
        VirtualMap vm = new VirtualMap(builder, CONFIGURATION);
        // Map is empty, firstLeafPath and lastLeafPath in dataSource are -1
        vm.fullLeafRehashIfNecessary();
        // No exception and logs would show skipping (hard to verify logs without mocks, but we can verify it doesn't
        // fail)
        vm.release();
    }

    @Test
    @DisplayName("Test rehash is skipped if first leaf hash matches")
    void testRehashSkippedIfHashMatches() throws IOException {
        final int chunkHeight =
                CONFIGURATION.getConfigData(VirtualMapConfig.class).hashChunkHeight();

        VirtualMap vm = new VirtualMap(builder, CONFIGURATION);
        VirtualMapMetadata metadata = vm.getMetadata();
        metadata.setLastLeafPath(1);
        metadata.setFirstLeafPath(1);

        final VirtualDataSource dataSource = vm.getDataSource();
        // Prepare data in data source
        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        Hash hash1 = hash(leaf1);
        final VirtualHashChunk chunk0 = new VirtualHashChunk(0, chunkHeight);
        chunk0.setHashAtPath(1, hash1);
        dataSource.saveRecords(1, 1, Stream.of(chunk0), Stream.of(leaf1), Stream.empty(), false);

        vm.fullLeafRehashIfNecessary();

        // Hash should still be the same
        assertEquals(hash1, vm.getRecords().findHash(1));
        vm.release();
    }

    @Test
    @DisplayName("Test rehash is triggered if first leaf hash does not match")
    void testRehashTriggeredIfHashMismatches() throws IOException {
        final int chunkHeight =
                CONFIGURATION.getConfigData(VirtualMapConfig.class).hashChunkHeight();

        VirtualMap vm = new VirtualMap(builder, CONFIGURATION);
        VirtualMapMetadata metadata = vm.getMetadata();
        metadata.setLastLeafPath(2);
        metadata.setFirstLeafPath(1);

        final VirtualDataSource dataSource = vm.getDataSource();
        // Prepare data in a data source with a wrong hash
        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        Hash correctHash = hash(leaf1);
        byte[] wrongHashBytes = new byte[48];
        wrongHashBytes[0] = 1; // Just to make it non-zero
        Hash wrongHash = new Hash(wrongHashBytes, Cryptography.DEFAULT_DIGEST_TYPE);

        // Also add a second leaf to make it a bit more interesting
        VirtualLeafBytes<TestValue> leaf2 = bananaLeaf(2);
        Hash correctHash2 = hash(leaf2);

        final VirtualHashChunk chunk0 = new VirtualHashChunk(0, chunkHeight);
        chunk0.setHashAtPath(1, wrongHash);
        chunk0.setHashAtPath(2, wrongHash);
        // Save with wrong hashes. Using a separate dataSource instance and builder to avoid any caching issues.
        dataSource.saveRecords(1, 2, Stream.of(chunk0), Stream.of(leaf1, leaf2), Stream.empty(), false);

        vm.fullLeafRehashIfNecessary();

        assertEquals(correctHash, vm.getRecords().findHash(1), "Hash for leaf 1 should be corrected");
        assertEquals(correctHash2, vm.getRecords().findHash(2), "Hash for leaf 2 should be corrected");

        // Internal node (path 0) should also be hashed
        assertEquals(
                VirtualHasher.hashInternal(correctHash, correctHash2),
                vm.getRecords().rootHash(),
                "Root hash should be computed");

        vm.release();
    }

    @Test
    @DisplayName("Test rehash fails with TimeoutException")
    void testRehashTimeout() throws IOException {
        // Prepare data in a data source with a wrong hash
        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        byte[] wrongHashBytes = new byte[48];
        wrongHashBytes[0] = 1;
        Hash wrongHash = new Hash(wrongHashBytes, Cryptography.DEFAULT_DIGEST_TYPE);

        // Configuration with 0ms timeout to ensure it times out
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(VirtualMapConfig.class)
                .withValue("virtualMap.fullRehashTimeoutMs", "0")
                .build();
        VirtualMap vm = new VirtualMap(builder, configuration);
        VirtualMapMetadata metadata = vm.getMetadata();
        metadata.setLastLeafPath(1);
        metadata.setFirstLeafPath(1);

        final VirtualDataSource dataSource = vm.getDataSource();
        final int chunkHeight = dataSource.getHashChunkHeight();
        final VirtualHashChunk chunk0 = new VirtualHashChunk(0, chunkHeight);
        chunk0.setHashAtPath(1, wrongHash);
        dataSource.saveRecords(1, 1, Stream.of(chunk0), Stream.of(leaf1), Stream.empty(), false);

        // This should throw MerkleSynchronizationException caused by TimeoutException
        final MerkleSynchronizationException exception =
                assertThrows(MerkleSynchronizationException.class, vm::fullLeafRehashIfNecessary);
        assertInstanceOf(
                TimeoutException.class,
                exception.getCause(),
                "Cause should be TimeoutException, but was: "
                        + (exception.getCause() == null
                                ? "null"
                                : exception.getCause().getClass().getName()));
    }
}
