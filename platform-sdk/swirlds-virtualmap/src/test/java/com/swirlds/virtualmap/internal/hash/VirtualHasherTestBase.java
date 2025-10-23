// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import com.hedera.pbj.runtime.io.buffer.Bytes;
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
        final VirtualHashRecord root = ds.getInternal(Path.ROOT_PATH);
        assert root != null;
        return hashSubTree(ds, md, root).hash();
    }

    protected static List<VirtualLeafBytes> invalidateNodes(final TestDataSource ds, final Stream<Long> dirtyPaths) {
        final List<VirtualLeafBytes> leaves = new ArrayList<>();
        dirtyPaths.forEach(i -> {
            final VirtualLeafBytes rec = ds.getLeaf(i);
            assert rec != null;
            leaves.add(rec);
            long path = rec.path();
            while (path >= 0) {
                final VirtualHashRecord internal = ds.getInternal(path);
                assert internal != null;
                ds.setInternal(new VirtualHashRecord(path));
                if (path == 0) {
                    break;
                }
                path = Path.getParentPath(path);
            }
        });
        return leaves;
    }

    protected static VirtualHashRecord hashSubTree(
            final TestDataSource ds, final MessageDigest md, final VirtualHashRecord internalNode) {
        final long leftChildPath = Path.getLeftChildPath(internalNode.path());
        VirtualHashRecord leftChild = ds.getInternal(leftChildPath);
        assert leftChild != null;
        final Hash leftHash;
        if (leftChildPath < ds.firstLeafPath) {
            leftChild = hashSubTree(ds, md, leftChild);
        }
        leftHash = leftChild.hash();

        final long rightChildPath = Path.getRightChildPath(internalNode.path());
        VirtualHashRecord rightChild = ds.getInternal(rightChildPath);
        Hash rightHash = null;
        if (rightChild != null) {
            if (rightChildPath < ds.firstLeafPath) {
                rightChild = hashSubTree(ds, md, rightChild);
            }
            rightHash = rightChild.hash();
        }

        // This has to match VirtualHasher
        md.reset();
        md.update((byte) 0x02);
        leftHash.getBytes().writeTo(md);
        if (rightHash != null) {
            rightHash.getBytes().writeTo(md);
        }
        final Hash hash = new Hash(md.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        VirtualHashRecord record = new VirtualHashRecord(internalNode.path(), hash);
        ds.setInternal(record);
        return record;
    }

    protected static final class TestDataSource {
        private final long firstLeafPath;
        private final long lastLeafPath;
        private final Map<Long, VirtualHashRecord> internals = new ConcurrentHashMap<>();

        TestDataSource(final long firstLeafPath, final long lastLeafPath) {
            this.firstLeafPath = firstLeafPath;
            this.lastLeafPath = lastLeafPath;
        }

        Hash loadHash(final long path) {
            if (path < Path.ROOT_PATH || path > lastLeafPath) {
                return null;
            }
            return getInternal(path).hash();
        }

        VirtualLeafBytes<TestValue> getLeaf(final long path) {
            if (path < firstLeafPath || path > lastLeafPath) {
                return null;
            }

            final Bytes key = TestKey.longToKey(path);
            final TestValue value = new TestValue("Value: " + path);
            return new VirtualLeafBytes<>(path, key, value, TestValueCodec.INSTANCE);
        }

        VirtualHashRecord getInternal(final long path) {
            if (path < Path.ROOT_PATH || path > lastLeafPath) {
                return null;
            }
            VirtualHashRecord rec = internals.get(path);
            if (rec == null) {
                final Hash hash;
                if (path < firstLeafPath) {
                    hash = Cryptography.NULL_HASH;
                } else {
                    final VirtualLeafBytes<TestValue> leaf = getLeaf(path);
                    assert leaf != null;
                    hash = hash(leaf);
                }
                rec = new VirtualHashRecord(path, hash);
            }
            return rec;
        }

        void setInternal(final VirtualHashRecord internal) {
            internals.put(internal.path(), internal);
        }
    }
}
