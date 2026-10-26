// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.node.app.records.impl.BlockRecordInfoUtils.RECORD_HASH_SIZE;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsPattern;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.buildDynamicJumpstartConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyJumpstartHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyLiveWrappedHash;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(RESTART)
@HapiTestLifecycle
@Order(Integer.MAX_VALUE - 2)
class JumpstartFileSuite implements LifecycleTest {

    // For excluding any of the 'non-core' nodes that are expected to be added, reconnected, or removed
    private static final long[] LATER_NODE_IDS = new long[] {4, 5, 6, 7, 8};

    @SuppressWarnings("DuplicatedCode")
    @LeakyHapiTest(
            overrides = {
                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk",
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
        final AtomicReference<BlockStreamJumpstartConfig> jumpstartConfig = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum = new AtomicReference<>();
        final AtomicReference<String> liveWrappedHash = new AtomicReference<>();
        final AtomicReference<String> liveBlockNum = new AtomicReference<>();
        final AtomicReference<BlockInfo> capturedBlockInfo = new AtomicReference<>();
        final AtomicReference<RunningHashes> capturedRunningHashes = new AtomicReference<>();
        final AtomicReference<BlockStreamJumpstartConfig> jumpstartConfig2 = new AtomicReference<>();
        final AtomicReference<String> nodeComputedHash2 = new AtomicReference<>();
        final AtomicReference<String> freezeBlockNum2 = new AtomicReference<>();

        // Mutable map so buildDynamicJumpstartConfig can add jumpstart config properties
        // before the restart reads them
        final var envOverrides =
                new HashMap<>(Map.of("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true"));

        // A 48-byte hash that will not match any real entry computed by buildDynamicJumpstartConfig
        final var corruptedHash = "aa".repeat(48);
        final AtomicReference<HashMap<String, String>> corruptedEnvOverridesRef = new AtomicReference<>();

        return hapiTest(
                doingContextual(spec -> org.junit.jupiter.api.Assumptions.assumeTrue(
                        spec.startupProperties().getStreamMode("blockStream.streamMode") != BLOCKS,
                        "Cutover jumpstart requires record/WRB; skipping in BLOCKS-only mode")),
                logIt("Phase 1: Writing wrapped record hashes to disk"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(envOverrides),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 2: Restart with corrupted hash to verify migration is skipped"),
                doingContextual(spec -> {
                    // Construct a _valid_ jumpstart dataset (populates envOverrides)
                    allRunFor(spec, buildDynamicJumpstartConfig(jumpstartConfig, envOverrides));

                    // Now overwrite the consensus-timestamp hash so it no longer matches the corresponding entry in the
                    // wrapped record hashes file on disk
                    final var corruptedEnvOverrides = new HashMap<>(envOverrides);
                    corruptedEnvOverrides.put(
                            "blockStream.jumpstart.currentBlockConsensusTimestampHash", corruptedHash);
                    corruptedEnvOverridesRef.set(corruptedEnvOverrides);
                }),
                prepareFakeUpgrade(),
                // Restart the network with the corrupted environment overrides
                doAdhoc(() -> upgradeToNextConfigVersion(
                        corruptedEnvOverridesRef.get(),
                        assertHgcaaLogContainsPattern(
                                NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                "Jumpstart currentBlockConsensusTimestampHash for block \\d+ does not match wrapped record hashes file entry",
                                Duration.ofSeconds(30)))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                logIt("Phase 3: Restarting with valid jumpstart config"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        envOverrides,
                        // Re-populate envOverrides with valid jumpstart data
                        buildDynamicJumpstartConfig(jumpstartConfig, envOverrides)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                logIt("Phase 4: Verify node can process transactions after jumpstart migration"),
                cryptoCreate("shouldWork").payingWith(GENESIS),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Migration root hash voting finalized after node\\d+ vote, >1/3 threshold reached",
                        Duration.ofSeconds(30)),
                logIt("Phase 5: Verify jumpstart file processed successfully"),
                assertHgcaaLogContainsPattern(
                                NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                "Completed processing all \\d+ recent wrapped record hashes\\. Final wrapped record block hash \\(as of expected freeze block (\\d+)\\): (\\S+)",
                                Duration.ofSeconds(30))
                        .exposingMatchGroupTo(1, freezeBlockNum)
                        .exposingMatchGroupTo(2, nodeComputedHash),
                // Independently verify the node's computed hash by replaying .rcd files from the
                // jumpstart block through the freeze block (the wrapped record hashes file itself
                // is truncated to empty as soon as the jumpstart migration consumes it, so it can't
                // be used for a post-hoc cross-check here).
                sourcing(
                        () -> verifyJumpstartHash(jumpstartConfig.get(), nodeComputedHash.get(), freezeBlockNum.get())),
                logIt("Phase 6: Verify a SECOND jumpstart cycle re-computes a distinct, correct hash"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        envOverrides,
                        // Re-populate envOverrides with a fresh jumpstart dataset (config B)
                        buildDynamicJumpstartConfig(jumpstartConfig2, envOverrides)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Initialized wrapped record voting singleton with deadline=\\d+",
                        Duration.ofSeconds(30)),
                // The second cycle must independently re-compute the jumpstart hash. Capture the most
                // recent "Completed processing" line (cycle 2's) and verify it as Phase 5 did for cycle 1.
                assertHgcaaLogContainsPattern(
                                NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                "Completed processing all \\d+ recent wrapped record hashes\\. Final wrapped record block hash \\(as of expected freeze block (\\d+)\\): (\\S+)",
                                Duration.ofSeconds(30))
                        .matchingLast()
                        .exposingMatchGroupTo(1, freezeBlockNum2)
                        .exposingMatchGroupTo(2, nodeComputedHash2),
                sourcing(() ->
                        verifyJumpstartHash(jumpstartConfig2.get(), nodeComputedHash2.get(), freezeBlockNum2.get())),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Migration root hash voting finalized after node\\d+ vote, >1/3 threshold reached",
                        Duration.ofSeconds(30)),
                // Verify cycle 2's voting handler did not replay stale queued hashes from cycle 1.
                // All "Applied queued hash for block{N}" entries processed by cycle 2's voting handler
                // must have block numbers > cycle 2's freeze block (F2). Stale entries from cycle 1's
                // voting window would have block numbers < F2 and would fail this assertion.
                sourcing(() -> doingContextual(spec -> {
                    final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
                    final String log;
                    try {
                        log = Files.readString(node0.getExternalPath(ExternalPath.APPLICATION_LOG));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    final int initIdx = log.lastIndexOf("Initialized wrapped record voting singleton with deadline=");
                    assertTrue(initIdx >= 0, "Expected to find cycle 2 voting init in log");
                    final var logAfterInit = log.substring(initIdx);
                    final var appliedPattern = Pattern.compile("Applied queued hash for block(\\d+):");
                    final var matcher = appliedPattern.matcher(logAfterInit);
                    final long cycle2FreezeBlock = Long.parseLong(freezeBlockNum2.get());
                    while (matcher.find()) {
                        final long blockNum = Long.parseLong(matcher.group(1));
                        assertTrue(
                                blockNum > cycle2FreezeBlock,
                                "Stale queued hash from prior jumpstart cycle detected: block "
                                        + blockNum
                                        + " <= cycle-2 freeze block "
                                        + cycle2FreezeBlock);
                    }
                })),
                // Distinct-by-construction: the second cycle used a different jumpstart block than the first.
                doAdhoc(() -> assertNotEquals(
                        jumpstartConfig.get().blockNum(),
                        jumpstartConfig2.get().blockNum(),
                        "Second jumpstart cycle should use a distinct jumpstart block from the first")),
                logIt("Phase 7: Third burst with live wrapped record hashes"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 8: Freeze and live hash verification"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(
                        Map.of(
                                "hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", "true",
                                "hedera.recordStream.computeHashesFromWrappedRecordBlocks", "false",
                                "hedera.recordStream.liveWritePrevWrappedRecordHashes", "true"),
                        assertHgcaaLogContainsPattern(
                                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                                        "Persisted live wrapped record block root hash \\(as of block (\\d+)\\): (\\S+)",
                                        Duration.ofSeconds(1))
                                .matchingLast()
                                .exposingMatchGroupTo(1, liveBlockNum)
                                .exposingMatchGroupTo(2, liveWrappedHash)),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Jumpstart migration already applied \\(votingComplete=true\\) and no jumpstart config, skipping",
                        Duration.ofSeconds(30)),
                sourcing(() -> verifyLiveWrappedHash(liveWrappedHash.get(), liveBlockNum.get())),
                logIt("Phase 9: Transition to SHA-256"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 9b: Enable SHA-256 block hashing"),
                prepareFakeUpgrade(),
                upgradeToNextConfigVersion(Map.of("blockStream.digestType", "SHA_256")),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                // Produce several SHA-256 blocks. The node logs a decoded BEFORE/AFTER dump of both trailing
                // windows at the SHA-384 -> SHA-256 transition block; verifyTrailingHashTransitionLogs() asserts
                // the exact values below, including that trailingBlockHashes stays 48-byte padded (no compaction).
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                doingContextual(JumpstartFileSuite::verifyTrailingBlockHashesMatchChain),
                logIt("Phase 10: Execute cutover"),
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
                                "BOTH",
                                // Overrides apply only to this restart, so keep the SHA-256 switch from Phase 9b
                                "blockStream.digestType",
                                "SHA_256"),
                        // Pre-restart: capture the final BlockInfo before cutover, and save preview block stream files
                        // for later replay
                        // Capture the genuine pre-cutover windows here, at the quiesced pre-restart point (the
                        // restarted node cannot reconstruct them once V0740 overwrites the singleton). The exact
                        // values are asserted by verifyCutoverLogFields below.
                        withOpContext((spec, opLog) ->
                                capturePreCutoverLogged(spec, opLog, capturedBlockInfo, capturedRunningHashes))),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Preview block stream overwrite executed; loading block stream info from cutover data",
                        Duration.ofSeconds(1)),
                // Verify logged BlockInfo fields match what we captured before the last restart
                doingContextual(spec -> verifyCutoverLogFields(spec, capturedBlockInfo, capturedRunningHashes)),
                // Verify the cutover transferred record stream state into the block stream correctly
                new VerifyCutoverBlockStreamOp(liveBlockNum, capturedBlockInfo, capturedRunningHashes),
                logIt("Phase 10: First post-cutover burst"),
                MixedOperations.burstOfTps(5, Duration.ofSeconds(30)),
                logIt("Phase 11: Verify clean cutover with additional blocks"),
                // Assert the node-logged, decoded trailing-window dumps at both transition blocks (SHA-384 ->
                // SHA-256 and the cutover): exact strides, per-entry sizes, and the key invariant that
                // trailingBlockHashes stays 48-byte padded after SHA-256 (compaction deferred past cutover).
                doingContextual(JumpstartFileSuite::verifyTrailingHashTransitionLogs),
                doingContextual(spec -> verifyPostCutoverBlocks(spec, liveBlockNum)),
                // restart with cutover enabled one more time, to verify it doesn't do anything
                logIt("Phase 12: Restart with cutover enabled to verify idempotent operation"),
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
                        "BOTH",
                        "blockStream.digestType",
                        "SHA_256")),
                waitForActive(NodeSelector.allNodes(), Duration.ofSeconds(60)),
                assertHgcaaLogContainsPattern(
                        NodeSelector.exceptNodeIds(LATER_NODE_IDS),
                        "Preview block stream info already overwritten, skipping cutover logic",
                        Duration.ofSeconds(1)),
                // Verify blocks are still produced after idempotent restart
                cryptoCreate("postIdempotentRestart").payingWith(GENESIS));
    }

    /**
     * Asserts the node-logged, decoded trailing-window dumps emitted at the two transition blocks (the SHA-384 -&gt;
     * SHA-256 digest transition and the block-stream cutover). Each transition is logged twice — a BEFORE/AT
     * snapshot and an AFTER snapshot — by {@code BlockStreamManagerImpl.logTrailingWindows}. This reads node 0's
     * application log instead of the live block stream, so it is immune to the mid-write partial-gzip race.
     *
     * <p>The key correctness assertion is that {@code trailingBlockHashes} is still at a 48-byte
     * {@code (pre-compaction)} stride in the AFTER-SHA-256 dump: proof that the switch to 32-byte block hashing did
     * NOT compact the window (compaction is deferred until well past cutover). Exact pre-/post-cutover values are
     * additionally pinned by {@link #verifyCutoverLogFields}.
     *
     * <p>A network that starts on SHA-256 has no digest switch (Phase 9b's upgrade changes nothing), so it must log
     * no transition, and its pre-cutover window is native rather than 48-byte padded.
     */
    private static void verifyTrailingHashTransitionLogs(final HapiSpec spec) {
        final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
        final String log;
        try {
            log = Files.readString(node0.getExternalPath(ExternalPath.APPLICATION_LOG));
        } catch (IOException e) {
            fail(e);
            return;
        }

        if (DigestType.valueOf(spec.startupProperties().get("blockStream.digestType")) == DigestType.SHA_256) {
            assertFalse(
                    log.contains("Trailing hashes BEFORE SHA-384 -> SHA-256 transition"),
                    "A network that starts on SHA-256 must not log a SHA-384 -> SHA-256 transition");
            trailingDumpSegment(log, "Trailing hashes BEFORE block-stream cutover (final pre-cutover window)");
            return;
        }

        // The strict correctness check is on trailingBlockHashes: it must stay at a 48-byte stride through all four
        // snapshots — never compacting when SHA-256 turns on, nor right up to cutover. (trailingOutputHashes is
        // logged verbatim in each dump for inspection, and its exact value at cutover is pinned by
        // verifyCutoverLogFields; its per-entry layout is a non-goal that self-heals after cutover.)

        // SHA-384 -> SHA-256 transition. BEFORE is decoded in the prior era, so it reads as a native 48-byte
        // SHA-384 window; AFTER keeps the 48-byte stride but now as (pre-compaction) under the shorter digest.
        // The real invariant is the stride: a 48-byte stride means the window has NOT compacted. (The dump labels
        // a 48-byte stride "(pre-compaction)"; a compacted window would show the shorter active-digest stride.)
        final var beforeSha256 = trailingDumpSegment(log, "Trailing hashes BEFORE SHA-384 -> SHA-256 transition");
        assertTrue(
                beforeSha256.contains("stride=48B"),
                "BEFORE SHA-256 dump should show a 48-byte trailingBlockHashes stride:\n" + beforeSha256);
        // The digest switches once (Phase 9b); the SHA-256 cutover must not log a second transition
        assertEquals(
                1L,
                Pattern.compile(Pattern.quote("Trailing hashes BEFORE SHA-384 -> SHA-256 transition"))
                        .matcher(log)
                        .results()
                        .count(),
                "Expected exactly one SHA-384 -> SHA-256 transition dump");

        final var afterSha256 = trailingDumpSegment(log, "Trailing hashes AFTER SHA-384 -> SHA-256 transition");
        assertTrue(
                afterSha256.contains("stride=48B"),
                "AFTER SHA-256 dump MUST still show a 48-byte trailingBlockHashes stride — the switch to SHA-256 "
                        + "must not compact the window:\n" + afterSha256);

        // Cutover: the genuine final pre-cutover window (logged by V0740BlockStreamSchema, which can still see it
        // before overwriting it) must still be at the 48-byte stride — proof that compaction is deferred right up
        // to cutover.
        final var beforeCutover =
                trailingDumpSegment(log, "Trailing hashes BEFORE block-stream cutover (final pre-cutover window)");
        assertTrue(
                beforeCutover.contains("stride=48B"),
                "BEFORE cutover dump should show a 48-byte trailingBlockHashes stride — compaction must still be "
                        + "deferred right up to cutover:\n" + beforeCutover);
    }

    /**
     * Extracts the text of a single trailing-window dump — from the given header line up to the next dump header
     * (or end of log) — so assertions on strides and sizes are scoped to that one snapshot. Fails if the header is
     * absent, which means the node never logged that transition.
     */
    private static String trailingDumpSegment(final String log, final String header) {
        final int start = log.indexOf(header);
        assertTrue(start >= 0, "Expected node to log a trailing-window dump with header: " + header);
        final int next = log.indexOf("Trailing hashes ", start + header.length());
        return next < 0 ? log.substring(start) : log.substring(start, next);
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
        // trailingBlockHashes = blockHashes minus the last record hash (off-by-one)
        final var fullBlockHashes = bi.blockHashes().toByteArray();
        final var expectedTrailingBlockHashes =
                Bytes.wrap(fullBlockHashes, 0, fullBlockHashes.length - RECORD_HASH_SIZE);
        assertLogContains(log, "trailingBlockHashes", expectedTrailingBlockHashes.toHex());
        // trailingOutputHashes must be exactly the final four record stream running hashes (SHA-384)
        final var rh = capturedRunningHashes.get();
        // Mirror V0740's zero-fill of empty predecessor running hashes so the expected window matches (four entries).
        Bytes expectedOutputHashes = BlockImplUtils.appendHash(
                BlockImplUtils.fullRecordRunningHash(rh.nMinus3RunningHash()), Bytes.EMPTY, 4);
        expectedOutputHashes = BlockImplUtils.appendHash(
                BlockImplUtils.fullRecordRunningHash(rh.nMinus2RunningHash()), expectedOutputHashes, 4);
        expectedOutputHashes = BlockImplUtils.appendHash(
                BlockImplUtils.fullRecordRunningHash(rh.nMinus1RunningHash()), expectedOutputHashes, 4);
        expectedOutputHashes = BlockImplUtils.appendHash(
                BlockImplUtils.fullRecordRunningHash(rh.runningHash()), expectedOutputHashes, 4);
        assertLogContains(log, "trailingOutputHashes", expectedOutputHashes.toHex());

        // Verify the logged RunningHashes hex values match what we captured
        assertLogContains(log, "runningHash", rh.runningHash().toHex());
        assertLogContains(log, "nMinus1", rh.nMinus1RunningHash().toHex());
        assertLogContains(log, "nMinus2", rh.nMinus2RunningHash().toHex());
        assertLogContains(log, "nMinus3", rh.nMinus3RunningHash().toHex());
        assertLogContains(log, "intermediatePreviousBlockRootHashes", bi.wrappedIntermediatePreviousBlockRootHashes());
        assertLogContains(log, "intermediateBlockRootsLeafCount", bi.wrappedIntermediateBlockRootsLeafCount());
    }

    /**
     * Decodes every persisted {@code trailingBlockHashes} window in node 0's block stream the way the node reads it
     * (stride from {@code trailing_block_hashes_compacted}, digest from the block header) and checks each entry
     * against the real hash of that block, i.e. the next block's footer {@code previousBlockRootHash}. Runs before
     * cutover, so it covers windows written before, at and after the SHA-384 -&gt; SHA-256 switch.
     */
    private static void verifyTrailingBlockHashesMatchChain(final HapiSpec spec) {
        final var node0 = spec.targetNetworkOrThrow().getRequiredNode(NodeSelector.byNodeId(0));
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkersAndReadErrors(
                node0.getExternalPath(BLOCK_STREAMS_DIR));
        final Map<Long, Bytes> realHashes = new HashMap<>();
        final Map<Long, Integer> digestLengths = new HashMap<>();
        final Map<Long, BlockStreamInfo> windows = new TreeMap<>();
        for (final var block : blocks) {
            long number = -1;
            for (final var item : block.items()) {
                if (item.hasBlockHeader()) {
                    final var header = item.blockHeaderOrThrow();
                    number = header.number();
                    digestLengths.put(number, header.hashAlgorithm() == BlockHashAlgorithm.SHA2_256 ? 32 : 48);
                } else if (item.hasBlockFooter()) {
                    realHashes.put(number - 1, item.blockFooterOrThrow().previousBlockRootHash());
                } else if (item.hasStateChanges()) {
                    for (final var change : item.stateChangesOrThrow().stateChanges()) {
                        if (change.hasSingletonUpdate()
                                && change.singletonUpdateOrThrow().hasBlockStreamInfoValue()) {
                            windows.put(number, change.singletonUpdateOrThrow().blockStreamInfoValueOrThrow());
                        }
                    }
                }
            }
        }

        int checked = 0;
        final List<String> mismatches = new ArrayList<>();
        for (final var entry : windows.entrySet()) {
            final var info = entry.getValue();
            final long lastInWindow = info.blockNumber() - 1;
            final int digestLength = digestLengths.get(entry.getKey());
            for (long n = Math.max(0, lastInWindow - 255); n <= lastInWindow; n++) {
                final var real = realHashes.get(n);
                if (real == null) {
                    continue;
                }
                checked++;
                final var decoded = BlockImplUtils.realBlockHashByBlockNumber(
                        info.trailingBlockHashes(), lastInWindow, n, digestLength, info.trailingBlockHashesCompacted());
                if (!real.equals(decoded)) {
                    mismatches.add("window of block " + entry.getKey() + " (compacted="
                            + info.trailingBlockHashesCompacted() + "): block " + n + " expected " + real.toHex()
                            + " but decoded " + (decoded == null ? "null" : decoded.toHex()));
                }
            }
        }
        assertTrue(checked > 0, "No trailing block hash entries could be checked against the chain");
        assertTrue(
                mismatches.isEmpty(),
                mismatches.size() + " of " + checked + " trailing block hash entries don't match the chain, e.g.:\n"
                        + String.join("\n", mismatches.subList(0, Math.min(5, mismatches.size()))));
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
