// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.HASH_SIZE;
import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.config.data.BlockStreamJumpstartConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.hedera.ExternalPath;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.upgrade.VerifyCutoverBlockStreamOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hedera.services.bdd.suites.regression.system.MixedOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;
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
                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                "blockStream.enableCutover",
                "blockStream.streamMode"
            })
    final Stream<DynamicTest> executesAllCutoverPhases() {
        final AtomicReference<List<WrappedRecordFileBlockHashes>> wrappedRecordHashes = new AtomicReference<>();
        final AtomicReference<BlockStreamJumpstartConfig> jumpstartConfig = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum = new AtomicReference<>();
        final AtomicReference<String> liveWrappedHash = new AtomicReference<>();
        final AtomicReference<String> liveBlockNum = new AtomicReference<>();
        final AtomicReference<BlockInfo> capturedBlockInfo = new AtomicReference<>();
        final AtomicReference<RunningHashes> capturedRunningHashes = new AtomicReference<>();

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
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(envOverrides, buildDynamicJumpstartConfig(jumpstartConfig, envOverrides)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
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
                                Duration.ofSeconds(1))
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
                sourcing(() -> verifyLiveWrappedHash(liveWrappedHash.get(), liveBlockNum.get())),
                logIt("Phase 7: Ops burst prior to cutover"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 8: Execute cutover"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                                "false",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                                "false",
                                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                                "false",
                                "blockStream.enableCutover",
                                "true",
                                "blockStream.streamMode",
                                // The real cutover value will be BLOCKS, but to keep record stream balance validation
                                // working, keep BOTH enabled
                                "BOTH"),
                        // Pre-restart: capture the final BlockInfo before cutover, and save preview block stream files
                        // for later replay
                        withOpContext((spec, opLog) ->
                                capturePreCutoverLogged(spec, opLog, capturedBlockInfo, capturedRunningHashes))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Block streams cutover executed; loading block stream info from cutover",
                        Duration.ofSeconds(1)),
                // Verify logged BlockInfo fields match what we captured before the last restart
                doingContextual(spec -> verifyCutoverLogFields(spec, capturedBlockInfo, capturedRunningHashes)),
                // Verify the cutover transferred record stream state into the block stream correctly
                new VerifyCutoverBlockStreamOp(liveBlockNum, capturedBlockInfo, capturedRunningHashes),
                logIt("Phase 9: First post-cutover burst"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 10: Verify clean cutover with additional blocks"),
                doingContextual(spec -> verifyPostCutoverBlocks(spec, liveBlockNum)),
                // restart with cutover enabled one more time, to verify it doesn't do anything
                logIt("Phase 11: Restart with cutover enabled to verify idempotent operation"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of(
                        "hedera.recordStream.computeHashesFromWrappedRecordBlocks",
                        "false",
                        "hedera.recordStream.liveWritePrevWrappedRecordHashes",
                        "false",
                        "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
                        "false",
                        "blockStream.enableCutover",
                        "true",
                        "blockStream.streamMode",
                        "BOTH")),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Block streams cutover not applicable, skipping",
                        Duration.ofSeconds(1)),
                // Verify blocks are still produced after idempotent restart
                cryptoCreate("postIdempotentRestart").payingWith(GENESIS));
    }

    private static void capturePreCutoverLogged(
            final HapiSpec spec,
            final Logger opLog,
            final AtomicReference<BlockInfo> capturedBlockInfo,
            final AtomicReference<RunningHashes> capturedRunningHashes)
            throws Exception {
        final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
        final var blockStreamsDir = node0.getExternalPath(BLOCK_STREAMS_DIR);
        final var allBlocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(blockStreamsDir);
        final var captured = BlockStreamAccess.computeSingletonValueFromUpdates(
                allBlocks, SingletonUpdateChange::blockInfoValue, 19);
        assertNotNull(captured, "BlockInfo should be present in block state changes");
        capturedBlockInfo.set(captured);
        // Also capture RunningHashes for trailing output hash verification
        final var capturedRH = BlockStreamAccess.computeSingletonValueFromUpdates(
                allBlocks, SingletonUpdateChange::runningHashesValue, 18);
        assertNotNull(capturedRH, "RunningHashes should be present in block state changes");
        capturedRunningHashes.set(capturedRH);
        opLog.info(
                "Captured pre-cutover BlockInfo: blockNum={}, blockHashesLen={}," + " runningHash={}",
                captured.lastBlockNumber(),
                captured.blockHashes().length(),
                capturedRH.runningHash().toHex());

        // Preserve preview block files so StateChangesValidator can replay state from genesis
        for (final var node : spec.targetNetworkOrThrow().nodesFor(NodeSelector.exceptNodeIds(LATER_NODE_IDS))) {
            final var srcDir = node.getExternalPath(BLOCK_STREAMS_DIR);
            final var destDir = node.metadata()
                    .workingDir()
                    .resolve("data")
                    .resolve("cutover")
                    .resolve("preservedPreviewBlocks");
            opLog.info("Preserving preview blocks from {} to {}", srcDir, destDir);
            copyDirectory(srcDir, destDir);
        }
    }

    private static void verifyCutoverLogFields(
            final HapiSpec spec,
            final AtomicReference<BlockInfo> capturedBlockInfo,
            final AtomicReference<RunningHashes> capturedRunningHashes) {
        final var bi = capturedBlockInfo.get();
        final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
        final String log;
        try {
            log = Files.readString(node0.getExternalPath(ExternalPath.APPLICATION_LOG));
        } catch (IOException e) {
            fail(e);
            return;
        }

        // Verify BlockInfo fields
        assertLogContains(log, "lastBlockNumber", bi.lastBlockNumber());
        assertLogContains(log, "blockHashesLength", bi.blockHashes().length());
        assertLogContains(
                log,
                "previousWrappedRecordBlockRootHash",
                bi.previousWrappedRecordBlockRootHash().toHex());
        assertLogContains(
                log,
                "wrappedIntermediateCount",
                bi.wrappedIntermediatePreviousBlockRootHashes().size());
        assertLogContains(log, "wrappedIntermediateLeafCount", bi.wrappedIntermediateBlockRootsLeafCount());

        // Verify block stream info fields derived from BlockInfo
        assertTrue(log.contains("Cutover initial BlockStreamInfo:"), "Log should contain cutover BSI dump");
        assertLogContains(log, "blockNumber", bi.lastBlockNumber());
        // trailingBlockHashes = blockHashes minus last HASH_SIZE (off-by-one)
        final var fullBlockHashes = bi.blockHashes().toByteArray();
        final var expectedTrailingBlockHashes = Bytes.wrap(fullBlockHashes, 0, fullBlockHashes.length - HASH_SIZE);
        assertLogContains(log, "trailingBlockHashes", expectedTrailingBlockHashes.toHex());
        // trailingOutputHashes must be exactly the final four record stream running hashes
        final var rh = capturedRunningHashes.get();
        Bytes expectedOutputHashes =
                BlockImplUtils.appendHash(Bytes.wrap(rh.nMinus3RunningHash().toByteArray()), Bytes.EMPTY, 4);
        expectedOutputHashes =
                BlockImplUtils.appendHash(Bytes.wrap(rh.nMinus2RunningHash().toByteArray()), expectedOutputHashes, 4);
        expectedOutputHashes =
                BlockImplUtils.appendHash(Bytes.wrap(rh.nMinus1RunningHash().toByteArray()), expectedOutputHashes, 4);
        expectedOutputHashes =
                BlockImplUtils.appendHash(Bytes.wrap(rh.runningHash().toByteArray()), expectedOutputHashes, 4);
        assertLogContains(log, "trailingOutputHashes", expectedOutputHashes.toHex());

        // Verify the logged RunningHashes hex values match what we captured
        assertLogContains(log, "runningHash", rh.runningHash().toHex());
        assertLogContains(log, "nMinus1", rh.nMinus1RunningHash().toHex());
        assertLogContains(log, "nMinus2", rh.nMinus2RunningHash().toHex());
        assertLogContains(log, "nMinus3", rh.nMinus3RunningHash().toHex());
        assertLogContains(log, "intermediatePreviousBlockRootHashes", bi.wrappedIntermediatePreviousBlockRootHashes());
        assertLogContains(log, "intermediateBlockRootsLeafCount", bi.wrappedIntermediateBlockRootsLeafCount());
    }

    private static void verifyPostCutoverBlocks(final HapiSpec spec, final AtomicReference<String> liveBlockNum) {
        final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
        final var blockStreamsDir = node0.getExternalPath(BLOCK_STREAMS_DIR);
        final var allBlocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(blockStreamsDir);
        final long liveBlock = Long.parseLong(liveBlockNum.get());

        final var postCutoverBlocks = allBlocks.stream()
                .filter(b -> b.items().stream()
                        .filter(BlockItem::hasBlockHeader)
                        .findFirst()
                        .map(item -> item.blockHeaderOrThrow().number() > liveBlock)
                        .orElse(false))
                .toList();
        assertTrue(
                postCutoverBlocks.size() >= 2,
                "Expected at least 2 post-cutover blocks after burst, found " + postCutoverBlocks.size());

        // Verify block numbers are sequential
        long prevBlockNum = -1;
        for (final var block : postCutoverBlocks) {
            final long blockNum = block.items().stream()
                    .filter(BlockItem::hasBlockHeader)
                    .findFirst()
                    .orElseThrow()
                    .blockHeaderOrThrow()
                    .number();
            if (prevBlockNum != -1) {
                assertEquals(prevBlockNum + 1, blockNum, "Block numbers should be sequential");
            }
            prevBlockNum = blockNum;
        }
    }

    private static void assertLogContains(final String log, final String key, final Object expected) {
        final var needle = key + "=" + expected;
        assertTrue(log.contains(needle), key + " mismatch (expected=" + expected + ", actual=<not found in log>)");
    }

    private static void copyDirectory(final Path src, final Path dest) throws java.io.IOException {
        try (final var walk = Files.walk(src)) {
            walk.forEach(source -> {
                final var target = dest.resolve(src.relativize(source));
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (final IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
