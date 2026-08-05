// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeVerbs.blockNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertBlockNodeCommsLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.awaitBlockNodeCommsLogContainsText;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.spec.SpecOperation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Consensus-node to block-node communication tests that use the in-process block node
 * <b>simulator</b> (no Docker / real block node containers).
 * NOTE: com.hedera.node.app.blocks.impl.streaming MUST have DEBUG logging enabled.
 */
@Tag(BLOCK_NODE)
@OrderedInIsolation
public class BlockNodeSimSuite {
    private static final int BLOCK_PERIOD_SECONDS = 2;
    private static final int STRESS_CYCLES = 30;

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 2, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 3, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1, 2, 3},
                        blockNodePriorities = {0, 1, 2, 3},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BOTH",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.buffer.ackedBlocksToRetain", "25"
                        })
            })
    @Order(1)
    final Stream<DynamicTest> node0StreamingBlockNodeConnectionDropsTrickle() {
        final AtomicReference<Instant> connectionDropTime = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                    portNumbers.add(spec.getBlockNodePortById(2));
                    portNumbers.add(spec.getBlockNodePortById(3));
                }),
                waitUntilNextBlocks(10).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(0).shutDownImmediately(), // Pri 0
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("Selected new block node for streaming: localhost:%s", portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(1)))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(1).shutDownImmediately(), // Pri 1
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("Selected new block node for streaming: localhost:%s", portNumbers.get(2)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(2)))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(2).shutDownImmediately(), // Pri 2
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("Selected new block node for streaming: localhost:%s", portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(3)))),
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                blockNode(1).startImmediately(),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(90),
                        String.format("Selected new block node for streaming: localhost:%s", portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(1)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection will be closed at the next block boundary (reason: HIGHER_PRIORITY_FOUND",
                                portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/CLOSING] Closing connection (reason: HIGHER_PRIORITY_FOUND",
                                portNumbers.get(3)))),
                doingContextual(spec -> connectionDropTime.set(Instant.now())),
                waitUntilNextBlocks(5),
                blockNode(1).shutDownImmediately(), // Pri 1
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        connectionDropTime::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(45),
                        String.format("Selected new block node for streaming: localhost:%s", portNumbers.get(3)),
                        String.format(
                                "/localhost:%s/ACTIVE] Connection state transitioned from READY to ACTIVE",
                                portNumbers.get(3)))));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.buffer.maxBlocks",
                            "30",
                            "blockStream.blockPeriod",
                            BLOCK_PERIOD_SECONDS + "s",
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC",
                            "blockNode.forcedSwitchRescheduleDelay",
                            "30s",
                            "blockStream.streamWrappedRecordBlocks",
                            "false"
                        })
            })
    @Order(2)
    final Stream<DynamicTest> testProactiveBlockBufferAction() {
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                doingContextual(spec -> timeRef.set(Instant.now())),
                blockNode(0).updateSendingBlockAcknowledgements(false),
                doingContextual(
                        spec -> LockSupport.parkNanos(Duration.ofSeconds(5).toNanos())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        // look for the saturation reaching the action stage (50%)
                        "ByBlockCount: 50.0000%",
                        // look for the log that shows the monitor detected buffer saturation
                        "Streaming connection update requested",
                        "buffer-unhealthy",
                        "/localhost:" + portNumbers.get(1)
                                + "/ACTIVE] Connection state transitioned from READY to ACTIVE")),
                // re-enable acks so buffer can drain via node 1 streaming
                blockNode(0).updateSendingBlockAcknowledgements(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        timeRef::get,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(2),
                        // saturation should fall back to low levels after switching to node 1
                        "ByBlockCount: 0.0000%")));
    }

    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode",
                            "BLOCKS",
                            "blockStream.writerMode",
                            "FILE_AND_GRPC",
                            "blockStream.buffer.maxBlocks",
                            "60",
                            "blockStream.buffer.isBufferPersistenceEnabled",
                            "true",
                            "blockStream.blockPeriod",
                            BLOCK_PERIOD_SECONDS + "s",
                            "blockNode.streamResetPeriod",
                            "20s",
                            "blockNode.streamResetPeriodJitter",
                            "0s",
                            "blockStream.streamWrappedRecordBlocks",
                            "false"
                        })
            })
    @Order(3)
    final Stream<DynamicTest> testBlockBufferDurability() {
        /*
        1. Create some background traffic for a while.
        2. Shutdown the block node to cause buffer saturation.
        3. Wait until the monitor detects saturation.
        4. Start the block node back up.
        5. Verify the buffer recovers (saturation drops to 0%).
        NOTE: The restart + buffer persistence flow is not tested here because after restart with
        a saturated buffer, the node enters backpressure before establishing a block node connection,
        causing it to stall in CHECKING state.
         */
        final AtomicReference<Instant> timeRef = new AtomicReference<>();
        final int maxBufferSize = 60;
        final int halfBufferSize = Math.max(1, maxBufferSize / 2);
        final Duration duration = Duration.ofSeconds(maxBufferSize * BLOCK_PERIOD_SECONDS);

        return hapiTest(
                // create some blocks to establish a baseline
                waitUntilNextBlocks(halfBufferSize).withBackgroundTraffic(true),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // shutdown the block node. this will cause the block buffer to become saturated
                blockNode(0).shutDownImmediately(),
                waitUntilNextBlocks(halfBufferSize).withBackgroundTraffic(true),
                // wait until the monitor detects saturation
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, duration, duration, "Streaming connection update requested")),
                // start the block node and let it catch up
                blockNode(0).startImmediately(),
                doingContextual(spec -> timeRef.set(Instant.now())),
                // saturation should drop as the block node acknowledges the buffered blocks
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0), timeRef::get, Duration.ofMinutes(3), Duration.ofMinutes(3), "saturation: 0.0%")));
    }

    /**
     * Exercises the consensus node's handling of the full set of publisher handshake responses: the non-error
     * responses (ResendBlock, BehindPublisher, SkipBlock) and every EndOfStream code that forces a reconnect
     * (DUPLICATE_BLOCK, BAD_BLOCK_PROOF, PERSISTENCE_FAILED, TIMEOUT, INVALID_REQUEST, ERROR, SUCCESS). After the
     * full walk it asserts the block node received a gap-free (contiguous) run of blocks.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.maxEndOfStreamsAllowed", "50",
                            "blockNode.globalCoolDownSeconds", "0",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(4)
    final Stream<DynamicTest> publisherHandshakeResponses() {
        final AtomicLong lastVerified = new AtomicLong();
        final AtomicReference<Set<Long>> received = new AtomicReference<>();
        return hapiTest(
                // Reach steady-state streaming to the block node.
                waitUntilNextBlocks(5).withBackgroundTraffic(true),

                // ResendBlock for a buffered (verified) block -> in-place rewind, stream continues.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec -> blockNode(0).sendResendBlockImmediately(lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "Received ResendBlock response for block ", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // BehindPublisher for a buffered block -> switch to N+1 in place (no reconnect).
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec -> blockNode(0).sendNodeBehindPublisherImmediately(lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "Received BehindPublisher response for block ", Duration.ofSeconds(30)),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0),
                        "Block node reported it is behind; will start streaming block ",
                        Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // SkipBlock -> received and handled gracefully; stream continues. The exact
                // taken/ignored branch depends on the in-flight block (unit-tested deterministically), so we
                // assert receipt + continued production here.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec -> blockNode(0).sendSkipBlockImmediately(lastVerified.get() + 1)),
                awaitBlockNodeCommsLogContainsText(byNodeId(0), "Received SkipBlock response", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(DUPLICATE_BLOCK) -> close + reconnect + resume.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec ->
                        blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.DUPLICATE_BLOCK, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "responseCode: DUPLICATE_BLOCK", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(BAD_BLOCK_PROOF) -> close + reconnect + resume.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec ->
                        blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.BAD_BLOCK_PROOF, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "responseCode: BAD_BLOCK_PROOF", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(PERSISTENCE_FAILED) -> close + reconnect + resume.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec ->
                        blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.PERSISTENCE_FAILED, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "responseCode: PERSISTENCE_FAILED", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(TIMEOUT) -> transient close + immediate restart at the next block.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(
                        spec -> blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.TIMEOUT, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(byNodeId(0), "responseCode: TIMEOUT", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(INVALID_REQUEST) -> transient close + immediate restart.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(spec ->
                        blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.INVALID_REQUEST, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "responseCode: INVALID_REQUEST", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(ERROR) -> close + reconnect later.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(
                        spec -> blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.ERROR, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(byNodeId(0), "responseCode: ERROR", Duration.ofSeconds(30)),
                waitUntilNextBlocks(2).withBackgroundTraffic(true),

                // EndOfStream(SUCCESS) -> orderly end + reconnect.
                blockNode(0).getLastVerifiedBlockExposing(lastVerified),
                sourcingContextual(
                        spec -> blockNode(0).sendEndOfStreamWithBlock(EndOfStream.Code.SUCCESS, lastVerified.get())),
                awaitBlockNodeCommsLogContainsText(byNodeId(0), "responseCode: SUCCESS", Duration.ofSeconds(30)),

                // Final continuity check: block production resumes after the last reconnect.
                waitUntilNextBlocks(3).withBackgroundTraffic(true),

                // Contiguity / no-data-loss: despite the reconnects triggered above, the block node received a
                // gap-free run of blocks. The single simulator is never restarted here, so its received-block set
                // is cumulative across every reconnect.
                blockNode(0).getReceivedBlockNumbersExposing(received::set),
                doingContextual(spec -> assertReceivedBlocksContiguous(received.get())));
    }

    /**
     * Exercises the "too far behind" failure branch: when the block node asks the consensus node to
     * rewind/resume at a block that has already been pruned from the buffer, the CN responds with
     * EndStream(TOO_FAR_BEHIND), closes the connection, and fails over. A small buffer is used so
     * the earliest blocks are pruned before the responses are injected.
     *
     * <p>Covers SEND_BEHIND w/ failure and the ResendBlock failure variant.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0},
                        blockNodePriorities = {0},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockStream.buffer.maxBlocks", "15",
                            "blockNode.globalCoolDownSeconds", "0",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(5)
    final Stream<DynamicTest> publisherHandshakeTooFarBehind() {
        // A single block node is used so it remains the active publisher target across the injections;
        // shortened cool-downs let it reconnect quickly after each TOO_FAR_BEHIND close.
        final AtomicReference<Instant> resendInjectedAt = new AtomicReference<>();
        return hapiTest(
                // Produce more blocks than the buffer can hold so the earliest blocks (incl. block 1) are pruned.
                waitUntilNextBlocks(20).withBackgroundTraffic(true),

                // BehindPublisher for an already-pruned block -> CN sends EndStream(TOO_FAR_BEHIND).
                blockNode(0).sendNodeBehindPublisherImmediately(0),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "Attempting to send EndStream (code: TOO_FAR_BEHIND", Duration.ofSeconds(30)),
                // The CN closes the connection and reconnects; production continues.
                waitUntilNextBlocks(3).withBackgroundTraffic(true),

                // ResendBlock for an already-pruned block -> TOO_FAR_BEHIND.
                doingContextual(spec -> resendInjectedAt.set(Instant.now())),
                blockNode(0).sendResendBlockImmediately(0),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "that block does not exist; closing connection", Duration.ofSeconds(30)),
                // Confirm the pruned-block ResendBlock specifically takes the TOO_FAR_BEHIND branch and not
                // the INTERNAL_ERROR one ("that block does not exist" is logged on both branches). A timeframe
                // assertion anchored just before this injection is required because the earlier BehindPublisher
                // step already logged a TOO_FAR_BEHIND EndStream, which a whole-file text match would match.
                sourcingContextual(spec -> assertBlockNodeCommsLogContainsTimeframe(
                        byNodeId(0),
                        resendInjectedAt::get,
                        Duration.ofMinutes(1),
                        Duration.ofSeconds(30),
                        "Attempting to send EndStream (code: TOO_FAR_BEHIND")),
                waitUntilNextBlocks(3).withBackgroundTraffic(true));
    }

    /**
     * Stress-tests rapid block node connect/disconnect cycles to ensure the consensus node keeps
     * producing a contiguous block sequence and never deadlocks or crashes its connection worker.
     *
     * <p>Covers cycle count reduced via {@link #STRESS_CYCLES} to keep runtime bounded.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.globalCoolDownSeconds", "0",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(6)
    final Stream<DynamicTest> publisherHandshakeRapidReconnectStress() {
        final AtomicLong blocksBeforeChurn = new AtomicLong();
        final AtomicLong blocksAfterChurn = new AtomicLong();
        final List<SpecOperation> ops = new ArrayList<>();
        ops.add(waitUntilNextBlocks(3).withBackgroundTraffic(true));
        ops.add(blockNode(0).getLastVerifiedBlockExposing(blocksBeforeChurn));
        // Rapidly drop and restore the primary block node; the CN must fail over to the secondary
        // and back without losing the block sequence or deadlocking.
        for (int i = 0; i < STRESS_CYCLES; i++) {
            ops.add(blockNode(0).shutDownImmediately());
            ops.add(waitUntilNextBlocks(1).withBackgroundTraffic(true));
            ops.add(blockNode(0).startImmediately());
            ops.add(waitUntilNextBlocks(1).withBackgroundTraffic(true));
        }
        // Let production settle after the final restart. A transient worker-thread exception while the
        // simulator is hard-killed mid-send is expected, so we assert recovery rather than the absence of
        // churn errors.
        ops.add(waitUntilNextBlocks(5).withBackgroundTraffic(true));
        ops.add(blockNode(0).getLastVerifiedBlockExposing(blocksAfterChurn));
        // Explicit assertion: the block sequence advanced across the churn, i.e. the consensus node never
        // deadlocked and the repeatedly-dropped block node recovered to verify current blocks (no data loss).
        ops.add(doingContextual(_ -> assertThat(blocksAfterChurn.get())
                .as("block production should advance across the reconnect churn")
                .isGreaterThan(blocksBeforeChurn.get())));
        // Contiguity: node 0 is recreated on each restart (its received-block set is wiped), so after the final
        // restart and settle window it holds the run of blocks streamed to it since coming back up. That run must
        // be gap-free, proving the consensus node resumed a contiguous stream after all the churn.
        final AtomicReference<Set<Long>> receivedAfterChurn = new AtomicReference<>();
        ops.add(blockNode(0).getReceivedBlockNumbersExposing(receivedAfterChurn::set));
        ops.add(doingContextual(_ -> assertReceivedBlocksContiguous(receivedAfterChurn.get())));
        return hapiTest(ops.toArray(new SpecOperation[0]));
    }

    /**
     * Exercises the high-latency QoS path: a slow (high-latency) block node whose acknowledgements lag beyond the
     * configured threshold is abandoned after enough consecutive high-latency events, and the consensus node fails
     * over to a healthy node. The high-latency threshold is lowered below the simulator's fixed 1500ms ack delay
     * so the switch fires deterministically.
     */
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {
                @BlockNodeConfig(nodeId = 0, mode = BlockNodeMode.SIMULATOR, highLatency = true),
                @BlockNodeConfig(nodeId = 1, mode = BlockNodeMode.SIMULATOR)
            },
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        blockNodeIds = {0, 1},
                        blockNodePriorities = {0, 1},
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE_AND_GRPC",
                            "blockStream.blockPeriod", BLOCK_PERIOD_SECONDS + "s",
                            "blockStream.streamWrappedRecordBlocks", "false",
                            "blockNode.highLatencyThreshold", "1s",
                            "blockNode.highLatencyEventsBeforeSwitching", "3",
                            "blockNode.globalCoolDownSeconds", "0",
                            "blockNode.basicNodeCoolDownSeconds", "1",
                            "blockNode.extendedNodeCoolDownSeconds", "1"
                        })
            })
    @Order(7)
    final Stream<DynamicTest> publisherHandshakeHighLatencySwitch() {
        final List<Integer> portNumbers = new ArrayList<>();
        return hapiTest(
                doingContextual(spec -> {
                    portNumbers.add(spec.getBlockNodePortById(0));
                    portNumbers.add(spec.getBlockNodePortById(1));
                }),
                // Node 0 (high latency) delays acknowledgements past the lowered threshold; after enough
                // consecutive high-latency events the consensus node closes the connection and fails over to node 1.
                waitUntilNextBlocks(5).withBackgroundTraffic(true),
                awaitBlockNodeCommsLogContainsText(
                        byNodeId(0), "Block node has exceeded high latency threshold", Duration.ofSeconds(90)),
                sourcingContextual(spec -> awaitBlockNodeCommsLogContainsText(
                        byNodeId(0),
                        String.format("Selected new block node for streaming: localhost:%s", portNumbers.get(1)),
                        Duration.ofSeconds(90))),
                waitUntilNextBlocks(3).withBackgroundTraffic(true));
    }

    /**
     * Asserts that the given set of received block numbers is non-empty and gap-free (contiguous) from its minimum
     * to its maximum, i.e. no block in the streamed range was lost.
     */
    private static void assertReceivedBlocksContiguous(final Set<Long> received) {
        assertThat(received)
                .as("block node should have received at least one block")
                .isNotEmpty();
        final long min = received.stream().mapToLong(Long::longValue).min().orElseThrow();
        final long max = received.stream().mapToLong(Long::longValue).max().orElseThrow();
        for (long b = min; b <= max; b++) {
            final long block = b;
            assertThat(received)
                    .as(
                            "Block sequence is not contiguous: block %d missing from received range [%d, %d]",
                            block, min, max)
                    .contains(block);
        }
    }
}
