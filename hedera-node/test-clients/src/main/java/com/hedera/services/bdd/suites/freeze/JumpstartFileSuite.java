// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildDynamicJumpstartConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getWrappedRecordHashes;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyJumpstartHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyLiveWrappedHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.services.bdd.suites.regression.system.MixedOperations;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;

@Tag(RESTART)
@HapiTestLifecycle
@Isolated
@Order(Integer.MAX_VALUE - 2)
class JumpstartFileSuite implements LifecycleTest {

    // For excluding any of the 'non-core' nodes that are expected to be added, reconnected, or removed
    private static final long[] LATER_NODE_IDS = new long[] {4, 5, 6, 7, 8};

    @SuppressWarnings("DuplicatedCode")
    @LeakyHapiTest(
            overrides = {
                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                "blockStream.jumpstart.blockNum",
                "blockStream.jumpstart.previousWrappedRecordBlockHash",
                "blockStream.jumpstart.streamingHasherLeafCount",
                "blockStream.jumpstart.streamingHasherHashCount",
                "blockStream.jumpstart.streamingHasherSubtreeHashes",
            })
    final Stream<DynamicTest> jumpstartsCorrectLiveWrappedRecordBlockHashes() {
        final AtomicReference<List<WrappedRecordFileBlockHashes>> wrappedRecordHashes = new AtomicReference<>();
        final AtomicReference<BlockStreamJumpstartConfig> jumpstartConfig = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum = new AtomicReference<>();
        final AtomicReference<String> liveWrappedHash = new AtomicReference<>();
        final AtomicReference<String> liveBlockNum = new AtomicReference<>();

        // Mutable map so buildDynamicJumpstartConfig can add jumpstart config properties
        // before the restart reads them
        final var envOverrides = new HashMap<>(Map.of(
                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                "true",
                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                "true",
                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                "true"));

        return hapiTest(
                // Any nodes added after genesis will not have a complete wrapped hashes file on disk, so shut them down
                logIt("Phase 1: Writing wrapped record hashes to disk"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 2: Restarting with jumpstart config"),
                upgradeToVersion("0.74.0", envOverrides, buildDynamicJumpstartConfig(jumpstartConfig, envOverrides)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertGetVersionInfoMatches(() -> SemanticVersion.newBuilder()
                        .setMajor(0)
                        .setMinor(74)
                        .setPatch(0)
                        .build()),
                logIt("Phase 3: Verify node can process transactions after jumpstart migration"),
                cryptoCreate("shouldWork").payingWith(GENESIS),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Migration root hash voting finalized after node\\d+ vote, >1/3 threshold reached",
                        Duration.ofSeconds(30)),
                logIt("Phase 4: Verify jumpstart file processed successfully"),
                assertHgcaaLogContainsPattern(
                                NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                "Completed processing all \\d+ recent wrapped record hashes\\. Final wrapped record block hash \\(as of expected freeze block (\\d+)\\): (\\S+)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, freezeBlockNum)
                        .exposingMatchGroupTo(2, nodeComputedHash),
                // Independently verify the node's computed hash. The wrapped record hashes file
                // may have grown since the migration ran (nodes continue writing after restart),
                // so we pass the freeze block number to bound the replay to the same range the
                // migration processed.
                getWrappedRecordHashes(wrappedRecordHashes),
                sourcing(() -> verifyJumpstartHash(
                        jumpstartConfig.get(),
                        wrappedRecordHashes.get(),
                        nodeComputedHash.get(),
                        freezeBlockNum.get())),
                logIt("Phase 5: Verify migration is not re-applied on restart"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(envOverrides),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertGetVersionInfoMatches(() -> SemanticVersion.newBuilder()
                        .setMajor(0)
                        .setMinor(74)
                        .setPatch(0)
                        .build()),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Jumpstart migration already applied \\(votingComplete=true\\), skipping",
                        Duration.ofSeconds(30)),
                logIt("Phase 6: Third burst with live wrapped record hashes"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 7: Freeze and live hash verification"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks", "false",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true"),
                        assertHgcaaLogContainsPattern(
                                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                        "Persisted live wrapped record block root hash \\(as of block (\\d+)\\): (\\S+)",
                                        Duration.ofSeconds(1))
                                .exposingMatchGroupTo(1, liveBlockNum)
                                .exposingMatchGroupTo(2, liveWrappedHash)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                sourcing(() -> verifyLiveWrappedHash(liveWrappedHash.get(), liveBlockNum.get())));
    }
}
