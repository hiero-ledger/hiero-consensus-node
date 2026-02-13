// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.hash;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.params.provider.Arguments;

public class VirtualHasherTestBase extends VirtualTestBase {

    protected static final int CHUNK_HEIGHT = VIRTUAL_MAP_CONFIG.hashChunkHeight();

    /**
     * Helper method for computing a list of {@link Arguments} of length {@code num}, each of which contains
     * a random list of dirty leave paths between {@code firstLeafPath} and {@code lastLeafPath}.
     *
     * @param num
     * 		The number of different random lists to create
     * @param firstLeafPath
     * 		The firstLeafPath
     * @param lastLeafPath
     * 		The lastLeafPath
     * @return
     * 		A non-null list of {@link Arguments} of random lists of paths.
     */
    protected static List<Arguments> randomDirtyLeaves(
            final int num, final long firstLeafPath, final long lastLeafPath) {
        final List<Arguments> args = new ArrayList<>();
        final Random rand = new Random(42);
        for (int i = 0; i < num; i++) {
            final int numDirtyLeaves = rand.nextInt((int) firstLeafPath);
            if (numDirtyLeaves == 0) {
                i--;
                continue;
            }
            final List<Long> paths = new ArrayList<>();
            for (int j = 0; j < numDirtyLeaves; j++) {
                paths.add(firstLeafPath + rand.nextInt((int) firstLeafPath));
            }
            args.add(Arguments.of(
                    firstLeafPath,
                    lastLeafPath,
                    paths.stream().sorted().distinct().collect(Collectors.toList())));
        }
        return args;
    }

    protected static Hash hashTree(final TestDataSource ds) throws NoSuchAlgorithmException {
        final MessageDigest md = MessageDigest.getInstance(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
        return hashSubTree(ds, md, Path.ROOT_PATH).hash();
    }

    @SuppressWarnings("rawtypes")
    protected static List<VirtualLeafBytes> invalidateNodes(final TestDataSource ds, final Stream<Long> dirtyPaths) {
        return dirtyPaths.peek(l -> ds.setHash(l, new Hash())).map(ds::getLeaf).collect(Collectors.toList());
    }

    protected static VirtualHashRecord hashSubTree(
            final TestDataSource ds, final MessageDigest md, final long nodePath) {
        final long leftChildPath = Path.getLeftChildPath(nodePath);
        final Hash leftHash;
        VirtualHashRecord leftChild;
        if (leftChildPath < ds.firstLeafPath) {
            leftChild = hashSubTree(ds, md, leftChildPath);
        } else {
            final VirtualLeafBytes<TestValue> leaf = ds.getLeaf(leftChildPath);
            assert leaf != null;
            leftChild = new VirtualHashRecord(leftChildPath, hash(leaf));
        }
        leftHash = leftChild.hash();
        ds.setHash(leftChildPath, leftHash);

        final long rightChildPath = Path.getRightChildPath(nodePath);
        Hash rightHash = null;
        VirtualHashRecord rightChild = null;
        if (rightChildPath < ds.firstLeafPath) {
            rightChild = hashSubTree(ds, md, rightChildPath);
        } else {
            final VirtualLeafBytes<TestValue> leaf = ds.getLeaf(rightChildPath);
            if (leaf != null) {
                rightChild = new VirtualHashRecord(rightChildPath, hash(leaf));
            }
        }
        if (rightChild != null) {
            rightHash = rightChild.hash();
            ds.setHash(rightChildPath, rightHash);
        }

        // This has to match VirtualHasher
        md.reset();
        md.update(rightHash == null ? (byte) 0x01 : (byte) 0x02);
        leftHash.getBytes().writeTo(md);
        if (rightHash != null) {
            rightHash.getBytes().writeTo(md);
        }
        final Hash hash = new Hash(md.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        ds.setHash(nodePath, hash);
        return new VirtualHashRecord(nodePath, hash);
    }

    protected static final class TestDataSource {

        private final long firstLeafPath;
        private final long lastLeafPath;

        private final int hashChunkHeight;

        // Chunk path to chunk
        private final Map<Long, VirtualHashChunk> chunks = new ConcurrentHashMap<>();

        TestDataSource(final long firstLeafPath, final long lastLeafPath, final int hashChunkHeight) {
            this.firstLeafPath = firstLeafPath;
            this.lastLeafPath = lastLeafPath;
            this.hashChunkHeight = hashChunkHeight;
        }

        VirtualHashChunk loadHashChunk(final long chunkPath) {
            if (chunkPath < Path.ROOT_PATH || chunkPath > lastLeafPath) {
                return null;
            }
            return chunks.get(chunkPath);
        }

        void updateHashChunk(final VirtualHashChunk chunk) {
            chunks.put(chunk.path(), chunk);
        }

        VirtualLeafBytes<TestValue> getLeaf(final long path) {
            if (path < firstLeafPath || path > lastLeafPath) {
                return null;
            }

            final Bytes key = TestKey.longToKey(path);
            final TestValue value = new TestValue("Value: " + path);
            return new VirtualLeafBytes<>(path, key, value, TestValueCodec.INSTANCE);
        }

        void setHash(final long path, final Hash hash) {
            if (path == ROOT_PATH) {
                return;
            }
            final int pathRank = Path.getRank(path);
            final boolean isLeaf = (path >= firstLeafPath) && (path <= lastLeafPath);
            if ((pathRank % hashChunkHeight != 0) && !isLeaf) {
                return;
            }
            final long chunkPath = VirtualHashChunk.pathToChunkPath(path, hashChunkHeight);
            chunks.compute(chunkPath, (p, chunk) -> {
                if (chunk == null) {
                    chunk = new VirtualHashChunk(chunkPath, hashChunkHeight);
                }
                chunk.setHashAtPath(path, hash);
                return chunk;
            });
        }
    }
}
