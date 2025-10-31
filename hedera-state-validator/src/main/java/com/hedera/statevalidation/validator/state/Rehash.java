// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.state;

import static com.hedera.statevalidation.util.ParallelProcessingUtils.VALIDATOR_FORK_JOIN_POOL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.junit.HashInfo;
import com.hedera.statevalidation.util.junit.HashInfoResolver;
import com.hedera.statevalidation.util.junit.StateResolver;
import com.swirlds.common.merkle.hash.FutureMerkleHash;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.AbstractTask;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, SlackReportGenerator.class, HashInfoResolver.class})
@Tag("rehash")
public class Rehash {

    private static final Logger logger = LogManager.getLogger(Rehash.class);

    /**
     * This parameter defines how deep the hash tree should be traversed.
     * Note that it doesn't go below the top level of VirtualMap even if the depth is set to a higher value.
     */
    public static final int HASH_DEPTH = 5;

    @Test
    void reHash(DeserializedSignedState deserializedSignedState) throws Exception {
        final VirtualMap vm = (VirtualMap)
                deserializedSignedState.reservedSignedState().get().getState().getRoot();
        records = vm.getRecords();

        final VirtualMapMetadata metadata = vm.getMetadata();
        firstLeafPath = metadata.getFirstLeafPath();
        lastLeafPath = metadata.getLastLeafPath();
        logger.info("Doing full rehash for the path range: {} - {}  in the VirtualMap", firstLeafPath, lastLeafPath);

        final long start = System.currentTimeMillis();
        result = new FutureMerkleHash();
        new TraverseTask(0, null).send();
        assertEquals(deserializedSignedState.originalHash(), result.get());
        logger.info("It took {} seconds to rehash the VirtualMap", (System.currentTimeMillis() - start) / 1000);
    }

    /**
     * This test validates the Merkle tree structure of the state.
     *
     * @param deserializedSignedState The deserialized signed state, propagated by the StateResolver.
     * @param hashInfo                The hash info object, propagated by the HashInfoResolver.
     */
    @Test
    void validateMerkleTree(DeserializedSignedState deserializedSignedState, HashInfo hashInfo) {

        var platformStateFacade = PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
        var infoStringFromState = platformStateFacade.getInfoString(
                deserializedSignedState.reservedSignedState().get().getState(), HASH_DEPTH);

        final var originalLines = Arrays.asList(hashInfo.content().split("\n")).getFirst();
        final var fullList = Arrays.asList(infoStringFromState.split("\n"));
        // skipping irrelevant lines, capturing only the one with the root hash
        final var revisedLines = filterLines(fullList);

        assertEquals(originalLines, revisedLines, "The Merkle tree structure does not match the expected state.");
    }

    private String filterLines(List<String> lines) {
        for (String line : lines) {
            if (line.contains("(root)")) {
                return line;
            }
        }
        return "root hash not found";
    }

    /**
     * This thread-local gets a message digest that can be used for hashing on a per-thread basis.
     */
    private static final ThreadLocal<WritableMessageDigest> MESSAGE_DIGEST_THREAD_LOCAL =
            ThreadLocal.withInitial(() -> new WritableMessageDigest(Cryptography.DEFAULT_DIGEST_TYPE.buildDigest()));

    private static final Hash NO_PATH2_HASH = new Hash();

    private RecordAccessor records;
    private long firstLeafPath;
    private long lastLeafPath;
    private FutureMerkleHash result;

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
                ComputeInternalHashTask hashTash = new ComputeInternalHashTask(path, parent);
                new TraverseTask(Path.getChildPath(path, 0), hashTash).send();
                new TraverseTask(Path.getChildPath(path, 1), hashTash).send();
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
