// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.junit.jupiter.api.Assertions;

/**
 * Verifies live wrapped record block hashes by replaying {@code .rcd} files from
 * genesis through the live-hash freeze block and asserting the final chained hash
 * matches the node's persisted live hash.
 *
 * <p>Per-block entry verification against the wrapped hashes file is handled
 * separately by {@link VerifyJumpstartHashOp} in Phase 4; this operation focuses
 * solely on the end-to-end chained hash correctness.
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
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0L);
        final var result = RcdFileBlockHashReplay.replay(spec, -1, endBlock, HASH_OF_ZERO, hasher);

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
