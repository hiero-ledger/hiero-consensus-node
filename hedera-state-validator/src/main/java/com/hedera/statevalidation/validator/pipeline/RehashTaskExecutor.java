// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.pipeline;

import static com.hedera.statevalidation.util.ParallelProcessingUtils.VALIDATOR_FORK_JOIN_POOL;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.statevalidation.util.FutureMerkleHash;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;

/**
 * Executes state rehash computation using tasks.
 */
public class RehashTaskExecutor {

    /**
     * This thread-local gets a message digest that can be used for hashing on a per-thread basis.
     */
    private static final ThreadLocal<WritableMessageDigest> MESSAGE_DIGEST_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> new WritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.buildDigest()));

    private static final Hash NO_PATH2_HASH = new Hash();

    private final RecordAccessor records;
    private final long firstLeafPath;
    private final long lastLeafPath;
    private final FutureMerkleHash result;

    /**
     * Creates a new RehashTaskExecutor.
     *
     * @param records the record accessor for reading leaf data
     * @param firstLeafPath the first leaf path in the virtual map
     * @param lastLeafPath the last leaf path in the virtual map
     */
    public RehashTaskExecutor(
            @NonNull final RecordAccessor records, final long firstLeafPath, final long lastLeafPath) {
        this.records = records;
        this.firstLeafPath = firstLeafPath;
        this.lastLeafPath = lastLeafPath;
        this.result = new FutureMerkleHash();
    }

    /**
     * Executes the rehash computation and returns the computed root hash.
     *
     * @return the computed root hash
     * @throws Exception if rehashing fails
     */
    public Hash execute() throws Exception {
        new TraverseTask(0, null).send();
        return result.get();
    }

    private class TraverseTask extends AbstractTask {
        final long path;
        final ComputeInternalHashTask parent;

        TraverseTask(final long path, final ComputeInternalHashTask parent) {
            super(VALIDATOR_FORK_JOIN_POOL, 0);
            this.path = path;
            this.parent = parent;
        }

        @Override
        protected boolean onExecute() {
            if (path < firstLeafPath) {
                // Internal node. Create traverse tasks recursively.
                ComputeInternalHashTask hashTask = new ComputeInternalHashTask(path, parent);
                new TraverseTask(Path.getChildPath(path, 0), hashTask).send();
                new TraverseTask(Path.getChildPath(path, 1), hashTask).send();
            } else {
                // Leaf node. Read and hash bytes.
                final VirtualLeafBytes<?> leafBytes = records.findLeafRecord(path);
                assert leafBytes != null;

                final WritableMessageDigest wmd = MESSAGE_DIGEST_THREAD_LOCAL.get();
                leafBytes.writeToForHashing(wmd);
                Hash hash = new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
                parent.setHash((leafBytes.path() & 1) == 1, hash);

                if (lastLeafPath == 1) {
                    parent.setHash(false, NO_PATH2_HASH);
                }
            }
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            result.cancelWithException(t);
        }
    }

    private class ComputeInternalHashTask extends AbstractTask {

        private final long path;
        private final ComputeInternalHashTask parent;
        private Hash leftHash;
        private Hash rightHash;

        ComputeInternalHashTask(final long path, final ComputeInternalHashTask parent) {
            super(VALIDATOR_FORK_JOIN_POOL, 2);
            this.path = path;
            this.parent = parent;
        }

        void setHash(boolean left, Hash hash) {
            if (left) {
                leftHash = hash;
            } else {
                rightHash = hash;
            }
            send();
        }

        @Override
        protected boolean onExecute() {
            final WritableMessageDigest wmd = MESSAGE_DIGEST_THREAD_LOCAL.get();
            wmd.writeByte((byte) 0x02);
            leftHash.getBytes().writeTo(wmd);
            if (rightHash != NO_PATH2_HASH) {
                rightHash.getBytes().writeTo(wmd);
            }
            Hash hash = new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);

            if (parent != null) {
                parent.setHash((path & 1) == 1, hash);
            } else {
                result.set(hash);
            }
            return true;
        }

        @Override
        protected void onException(final Throwable t) {
            result.cancelWithException(t);
        }
    }
}
