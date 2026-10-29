// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Assertions;

/**
 * Verifies live wrapped record block hashes by replaying {@code .rcd} files from
 * genesis through the live-hash freeze block and asserting the final chained hash
 * matches the node's persisted live hash.
 */
public class VerifyLiveWrappedHashOp extends UtilOp {

    private final String nodeComputedHash;
    private final String liveBlockNum;

    /**
     * @param nodeComputedHash the hash the node persisted (from log scraping)
     * @param liveBlockNum     the block number the node persisted its live hash at
     */
    public VerifyLiveWrappedHashOp(@NonNull final String nodeComputedHash, @NonNull final String liveBlockNum) {
        this.nodeComputedHash = requireNonNull(nodeComputedHash);
        this.liveBlockNum = requireNonNull(liveBlockNum);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final long endBlock = Long.parseLong(liveBlockNum);

        // Replay .rcd files from genesis through the live-hash block
        final var digestTypeName = spec.startupProperties().get("blockStream.digestType");
        final var digestType = digestTypeName != null ? DigestType.valueOf(digestTypeName) : DigestType.SHA_384;
        final var hasher = new IncrementalStreamingHasher(CommonUtils.digestOrThrow(digestType), List.of(), 0L);
        final var result = RcdFileBlockHashReplay.replay(
                spec, -1, endBlock, BlockStreamManager.hashOfZero(digestType), hasher, digestType);

        // Final hash assertion: .rcd chain vs node logged hash
        Assertions.assertEquals(
                nodeComputedHash,
                result.finalChainedHash().toString(),
                ("[VerifyLiveWrappedHash] Mismatch after processing %d blocks up to live block %d."
                                + " Check node logs for 'Persisted live wrapped record block root hash'.")
                        .formatted(result.blocksProcessed(), endBlock));
        return false;
    }
}
