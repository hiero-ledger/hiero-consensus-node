// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.Hash;
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

    public static Hash hash(final long t) {
        try {
            final MessageDigest md = MessageDigest.getInstance(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
            final ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
            buf.putLong(t);
            md.update(buf);
            return new Hash(md.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e);
        }
    }

    public static Hash hash(final VirtualLeafBytes<?> rec) {
        try {
            final MessageDigest md = MessageDigest.getInstance(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
            final WritableMessageDigest wmd = new WritableMessageDigest(md);
            rec.writeToForHashing(wmd);
            return new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e);
        }
    }

    public static Hash loadHash(final VirtualDataSource dataSource, final long path, final int hashChunkHeight)
            throws IOException {
        if (path > dataSource.getLastLeafPath()) {
            return null;
        }
        final long hashChunkId = VirtualHashChunk.pathToChunkId(path, hashChunkHeight);
        final VirtualHashChunk hashChunk = dataSource.loadHashChunk(hashChunkId);
        if (hashChunk == null) {
            return null;
        }
        return hashChunk.calcHash(path, dataSource.getFirstLeafPath(), dataSource.getLastLeafPath());
    }

    public static Stream<VirtualHashChunk> createHashChunkStream(
            final int hashChunkHeight, final VirtualHashRecord... hashRecords) {
        final Map<Long, VirtualHashChunk> hashChunks = new HashMap<>();
        for (final VirtualHashRecord rec : hashRecords) {
            final long path = rec.path();
            final long chunkId = VirtualHashChunk.pathToChunkId(path, hashChunkHeight);
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
            final VirtualHashChunk chunk =
                    hashChunks.computeIfAbsent(chunkId, id -> new VirtualHashChunk(chunkPath, hashChunkHeight));
            chunk.setHashAtPath(path, rec.hash());
        }
        return hashChunks.values().stream().sorted(Comparator.comparingLong(VirtualHashChunk::path));
    }

    public static Stream<VirtualHashChunk> createHashChunkStream(
            final int hashChunkHeight, final List<VirtualLeafBytes> leafRecords) {
        final Map<Long, VirtualHashChunk> hashChunks = new HashMap<>();
        for (final VirtualLeafBytes rec : leafRecords) {
            final long path = rec.path();
            final long chunkId = VirtualHashChunk.pathToChunkId(path, hashChunkHeight);
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
            final VirtualHashChunk chunk =
                    hashChunks.computeIfAbsent(chunkId, id -> new VirtualHashChunk(chunkPath, hashChunkHeight));
            chunk.setHashAtPath(path, hash(rec));
        }
        return hashChunks.values().stream().sorted(Comparator.comparingLong(VirtualHashChunk::path));
    }

    public static long hashChunkStreamSize(final int hashChunkHeight, final long startPathInc, final long endPathExc) {
        final Set<Long> chunkIds = new HashSet<>();
        for (long path = startPathInc; path < endPathExc; path++) {
            final long chunkId = VirtualHashChunk.pathToChunkId(path, hashChunkHeight);
            chunkIds.add(chunkId);
        }
        return chunkIds.size();
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

        for (long i = 1; i <= originalMapMetadata.getLastLeafPath(); i++) {
            assertEquals(
                    originalMap.getRecords().findHash(i),
                    deserializedMap.getRecords().findHash(i),
                    "hashes should match");
        }
    }
}
