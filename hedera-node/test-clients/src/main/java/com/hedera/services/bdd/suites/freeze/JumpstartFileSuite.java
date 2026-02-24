// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogDoesNotContainText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildDynamicJumpstartFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getWrappedRecordHashes;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyJumpstartHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.services.bdd.suites.regression.system.MixedOperations;
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

    @LeakyHapiTest(overrides = {"hedera.recordStream.computeHashesFromWrappedRecordBlocks"})
    final Stream<DynamicTest> generatesJumpstart() {
        final AtomicReference<List<WrappedRecordFileBlockHashes>> wrappedRecordHashes = new AtomicReference<>();
        final AtomicReference<byte[]> jumpstartFileContents = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum = new AtomicReference<>();

        return hapiTest(
                overriding("hedera.recordStream.computeHashesFromWrappedRecordBlocks", "true"),
                logIt("Phase 1: Writing wrapped record hashes to disk"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(60)),
                logIt("Phase 2: Restarting with jumpstart file"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of("hedera.recordStream.computeHashesFromWrappedRecordBlocks", "true"),
                        buildDynamicJumpstartFile(jumpstartFileContents)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                logIt("Phase 3: Verify node can process transactions after migration"),
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
                // Independently verify the node's computed hash. The wrapped record hashes file
                // may have grown since the migration ran (nodes continue writing after restart),
                // so we pass the freeze block number to bound the replay to the same range the
                // migration processed.
                getWrappedRecordHashes(wrappedRecordHashes),
                sourcing(() -> verifyJumpstartHash(
                        jumpstartFileContents.get(),
                        wrappedRecordHashes.get(),
                        nodeComputedHash.get(),
                        freezeBlockNum.get())));
    }
}
