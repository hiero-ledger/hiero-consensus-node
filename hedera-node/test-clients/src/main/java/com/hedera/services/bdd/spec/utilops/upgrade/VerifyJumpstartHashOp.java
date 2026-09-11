// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * Verifies the node's jumpstart hash computation by independently replaying {@code .rcd} files
 * from the jumpstart block through the freeze block and comparing the final chained hash against
 * the node's logged hash.
 *
 * <p>This cannot also cross-check against the wrapped record hashes file's raw entries, because
 * that file is truncated to empty as soon as a jumpstart migration consumes it (see
 * {@code WrappedRecordBlockHashMigration}); by the time this op runs, the entries it would need
 * are already gone from disk.
 */
public class VerifyJumpstartHashOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(VerifyJumpstartHashOp.class);

    private final BlockStreamJumpstartConfig jumpstartConfig;
    private final String nodeComputedHash;
    private final String freezeBlockNum;

    public VerifyJumpstartHashOp(
            @NonNull final BlockStreamJumpstartConfig jumpstartConfig,
            @NonNull final String nodeComputedHash,
            @NonNull final String freezeBlockNum) {
        this.jumpstartConfig = requireNonNull(jumpstartConfig);
        this.nodeComputedHash = requireNonNull(nodeComputedHash);
        this.freezeBlockNum = requireNonNull(freezeBlockNum);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        final long freezeBlock = Long.parseLong(freezeBlockNum);
        final long jumpstartBlockNum = jumpstartConfig.blockNum();
        final Bytes prevHash = jumpstartConfig.previousWrappedRecordBlockHash();
        final var hasher = createHasherFromConfig(jumpstartConfig);

        log.info(
                "[VerifyJumpstartHash] Jumpstart block={}, prevHash={}, freeze block={}",
                jumpstartBlockNum,
                prevHash,
                freezeBlock);

        final var rcdResult = RcdFileBlockHashReplay.replay(spec, jumpstartBlockNum, freezeBlock, prevHash, hasher);

        log.info(
                "[VerifyJumpstartHash] .rcd replay processed {} blocks, final hash: {}",
                rcdResult.blocksProcessed(),
                rcdResult.finalChainedHash());

        Assertions.assertEquals(
                nodeComputedHash,
                rcdResult.finalChainedHash().toString(),
                ("[VerifyJumpstartHash] .rcd chain mismatch after processing %d blocks."
                                + " jumpstart block=%d, freeze block=%d."
                                + " Check node logs for 'Persisted' wrapped hash entries.")
                        .formatted(rcdResult.blocksProcessed(), jumpstartBlockNum, freezeBlock));

        log.info(
                "[VerifyJumpstartHash] Verification passed: rcd chain={}, node logged={}",
                rcdResult.finalChainedHash(),
                nodeComputedHash);

        return false;
    }

    private static IncrementalStreamingHasher createHasherFromConfig(@NonNull final BlockStreamJumpstartConfig config) {
        final var subtreeHashes = config.streamingHasherSubtreeHashes();
        final List<byte[]> hashes = new ArrayList<>(subtreeHashes.size());
        for (final var hash : subtreeHashes) {
            hashes.add(hash.toByteArray());
        }
        return new IncrementalStreamingHasher(sha384DigestOrThrow(), hashes, config.streamingHasherLeafCount());
    }
}
