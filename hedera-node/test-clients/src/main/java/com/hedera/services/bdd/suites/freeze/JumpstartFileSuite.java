// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildDynamicJumpstartFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getWrappedRecordHashes;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyJumpstartHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyLiveWrappedHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.upgrade.GetWrappedRecordHashesOp.CLASSIC_NODE_IDS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.upgrade.RemoveNodeOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.services.bdd.suites.regression.system.MixedOperations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
                "hedera.recordStream.liveWritePrevWrappedRecordHashes"
            })
    final Stream<DynamicTest> jumpstartsCorrectLiveWrappedRecordBlockHashes() {
        final AtomicReference<List<WrappedRecordFileBlockHashes>> wrappedRecordHashes = new AtomicReference<>();
        final AtomicReference<byte[]> jumpstartFileContents = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum = new AtomicReference<>();
        final AtomicReference<String> liveWrappedHash = new AtomicReference<>();
        final AtomicReference<String> liveBlockNum = new AtomicReference<>();

        return hapiTest(
                overriding("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true"),
                // Any nodes added after genesis will not have a complete wrapped hashes file on disk, so shut them down
                logIt("Phase 0: shut down extra nodes (if any)"),
                doingContextual(spec -> {
                    final var nodesToShutDown = spec.targetNetworkOrThrow().nodes().stream()
                            .filter(node -> !CLASSIC_NODE_IDS.contains(node.getNodeId()))
                            .map(node -> FakeNmt.removeNode(NodeSelector.byNodeId(node.getNodeId())))
                            .toList();
                    if (!nodesToShutDown.isEmpty()) {
                        allRunFor(spec, nodesToShutDown.toArray(new RemoveNodeOp[0]));
                    }
                }),
                logIt("Phase 1: Writing wrapped record hashes to disk"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 2: Restarting with jumpstart file"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                                "true",
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                                "true",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                                "true"),
                        buildDynamicJumpstartFile(jumpstartFileContents)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                logIt("Phase 3: Verify node can process transactions after jumpstart migration"),
                cryptoCreate("shouldWork").payingWith(GENESIS),
                logIt("Phase 4: Verify jumpstart file processed successfully"),
                assertHgcaaLogDoesNotContainText(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Resuming calculation of wrapped record file hashes until next attempt, but this node will likely experience an ISS",
                        Duration.ofSeconds(30)),
                assertHgcaaLogContainsPattern(
                                NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                "Completed processing all \\d+ recent wrapped record hashes\\. Final wrapped record block hash \\(as of expected freeze block (\\d+)\\): (\\S+)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, freezeBlockNum)
                        .exposingMatchGroupTo(2, nodeComputedHash),
                // Verify the jumpstart file was archived after successful migration
                withOpContext((spec, opLog) -> {
                    for (final var node : spec.targetNetworkOrThrow().nodes()) {
                        if (!CLASSIC_NODE_IDS.contains(node.getNodeId())) {
                            continue;
                        }

                        final var workingDir = requireNonNull(node.metadata().workingDir());
                        final var cutoverDir = workingDir.resolve(Path.of("data", "cutover"));
                        final var original = cutoverDir.resolve("jumpstart.bin");
                        org.junit.jupiter.api.Assertions.assertFalse(
                                Files.exists(original),
                                "Jumpstart file should have been archived on node " + node.getNodeId()
                                        + " but still exists at " + original);
                        final var archived = cutoverDir.resolve("archived_jumpstart.bin");
                        org.junit.jupiter.api.Assertions.assertTrue(
                                Files.exists(archived),
                                "Archived jumpstart file not found on node " + node.getNodeId() + " at " + archived);
                    }
                }),
                // Independently verify the node's computed hash. The wrapped record hashes file
                // may have grown since the migration ran (nodes continue writing after restart),
                // so we pass the freeze block number to bound the replay to the same range the
                // migration processed.
                getWrappedRecordHashes(wrappedRecordHashes),
                sourcing(() -> verifyJumpstartHash(
                        jumpstartFileContents.get(),
                        wrappedRecordHashes.get(),
                        nodeComputedHash.get(),
                        freezeBlockNum.get())),
                // Phase 5: Second burst with live wrapped record hashes enabled
                logIt("Phase 5: Second burst with live wrapped record hashes"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                // Phase 6: Second freeze to persist live hash state, then verify from record files
                logIt("Phase 6: Second freeze and live hash verification"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                                "false",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                                "true"),
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
