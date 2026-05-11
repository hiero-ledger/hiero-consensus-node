// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Verifies live wrapped record block hashes by replaying {@code .rcd} files from the Phase 6
 * freeze block through the live-hash freeze block and asserting the final chained hash matches
 * the node's persisted live hash.
 *
 * <p>The replay is seeded from the Phase 6 freeze state (block number, prev hash, leaf count,
 * and intermediate hashes) captured from the node's {@code [LIVE_HASH_INIT]} log line, so that
 * the {@code allPrevBlocksRootHash} at each step matches what the node computed live.
 *
 * <p>Per-block entry verification against the wrapped hashes file is handled separately by
 * {@link VerifyJumpstartHashOp} in Phase 4; this operation focuses solely on the chained
 * hash correctness from Phase 6 onward.
 */
public class VerifyLiveWrappedHashOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(VerifyLiveWrappedHashOp.class);

    private final String nodeComputedHash;
    private final String liveBlockNum;
    private final String priorFreezeBlockNum;
    private final String priorFreezePrevHash;
    private final String priorFreezeLeafCount;
    private final String priorFreezeIntermediateHashes;

    /**
     * @param nodeComputedHash             the hash the node persisted (from log scraping)
     * @param liveBlockNum                 the block number the node persisted its live hash at
     * @param priorFreezeBlockNum          the {@code lastBlockNumber} from Phase 6's LIVE_HASH_INIT log
     * @param priorFreezePrevHash          the {@code prevWrappedBlockHash} from Phase 6's LIVE_HASH_INIT log
     * @param priorFreezeLeafCount         the {@code hasherLeafCount} from Phase 6's LIVE_HASH_INIT log
     * @param priorFreezeIntermediateHashes comma-separated hex hashes from Phase 6's LIVE_HASH_INIT log
     */
    public VerifyLiveWrappedHashOp(
            @NonNull final String nodeComputedHash,
            @NonNull final String liveBlockNum,
            @NonNull final String priorFreezeBlockNum,
            @NonNull final String priorFreezePrevHash,
            @NonNull final String priorFreezeLeafCount,
            @NonNull final String priorFreezeIntermediateHashes) {
        this.nodeComputedHash = requireNonNull(nodeComputedHash);
        this.liveBlockNum = requireNonNull(liveBlockNum);
        this.priorFreezeBlockNum = requireNonNull(priorFreezeBlockNum);
        this.priorFreezePrevHash = requireNonNull(priorFreezePrevHash);
        this.priorFreezeLeafCount = requireNonNull(priorFreezeLeafCount);
        this.priorFreezeIntermediateHashes = requireNonNull(priorFreezeIntermediateHashes);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final long endBlock = Long.parseLong(liveBlockNum);
        // priorFreezeBlockNum is lastBlockNumber from LIVE_HASH_INIT; the actual freeze block
        // (lastBlockNumber+1) is already included as a leaf in the restored hasher, so skip it.
        final long startBlock = Long.parseLong(priorFreezeBlockNum) + 1;
        final var initialPrevHash = Bytes.wrap(HexFormat.of().parseHex(priorFreezePrevHash));
        final long leafCount = Long.parseLong(priorFreezeLeafCount);
        final List<byte[]> subtreeHashes = Arrays.stream(priorFreezeIntermediateHashes.split(","))
                .filter(s -> !s.isEmpty())
                .map(h -> HexFormat.of().parseHex(h))
                .toList();

        log.info(
                "[VerifyLiveWrappedHash] Starting replay from Phase 6 freeze state."
                        + " startBlock={} endBlock={} leafCount={} subtreeHashCount={} nodeComputedHash={}",
                startBlock,
                endBlock,
                leafCount,
                subtreeHashes.size(),
                nodeComputedHash);

        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), subtreeHashes, leafCount);
        final var result = RcdFileBlockHashReplay.replay(spec, startBlock, endBlock, initialPrevHash, hasher);

        log.info(
                "[VerifyLiveWrappedHash] Replay complete."
                        + " endBlock={} blocksProcessed={} nodeHash={} replayHash={} match={}",
                endBlock,
                result.blocksProcessed(),
                nodeComputedHash,
                result.finalChainedHash(),
                nodeComputedHash.equals(result.finalChainedHash().toString()));

        Assertions.assertEquals(
                nodeComputedHash,
                result.finalChainedHash().toString(),
                ("[VerifyLiveWrappedHash] Mismatch after processing %d blocks up to live block %d."
                                + " Check node logs for 'Persisted live wrapped record block root hash'.")
                        .formatted(result.blocksProcessed(), endBlock));
        return false;
    }
}
