// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE_SIMULATOR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeSimulatorVerbs.blockNodeSimulator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;

import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * This suite is for testing with the block node simulator.
 *
 * <p>Note: This suite requires the block node simulator to be enabled.
 * Use the system property "hapi.spec.blocknode.mode=SIM" to enable it.
 * The testSubprocessWithBlockNodeSimulator task automatically sets this property.
 */
@Tag(BLOCK_NODE_SIMULATOR)
@OrderedInIsolation
public class BlockNodeSimulatorSuite {

    @HapiTest
    final Stream<DynamicTest> nominalStreamingBlocksNoErrors() {
        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockInternalError() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5), // Timeframe window to search for messages
                        Duration.ofSeconds(20), // Wait timeout for messages to appear
                        "Received EndOfStream from block node localhost",
                        "Restarting stream at block 0 due to STREAM_ITEMS_INTERNAL_ERROR for node localhost",
                        "Ending stream and restarting at block 0 for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Set current block number to 0 for node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost",
                        "Stream ended and restarted at block 0 for node localhost"));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockTimeout() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_TIMEOUT)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        "Received EndOfStream from block node localhost",
                        "Restarting stream at block 0 due to STREAM_ITEMS_TIMEOUT for node localhost",
                        "Ending stream and restarting at block 0 for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Set current block number to 0 for node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost",
                        "Stream ended and restarted at block 0 for node localhost"));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockOutOfOrder() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_OUT_OF_ORDER)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        "Received EndOfStream from block node localhost",
                        "Restarting stream at block 0 due to STREAM_ITEMS_OUT_OF_ORDER for node localhost",
                        "Ending stream and restarting at block 0 for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Set current block number to 0 for node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost",
                        "Stream ended and restarted at block 0 for node localhost"));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockBadStateProof() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_BAD_STATE_PROOF)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        "Received EndOfStream from block node localhost",
                        "Restarting stream at block 0 due to STREAM_ITEMS_BAD_STATE_PROOF for node localhost",
                        "Ending stream and restarting at block 0 for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Set current block number to 0 for node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost",
                        "Stream ended and restarted at block 0 for node localhost"));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockBehind() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_BEHIND)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        "Received EndOfStream from block node localhost",
                        "Restarting stream at block 0 due to STREAM_ITEMS_BEHIND for node localhost",
                        "Ending stream and restarting at block 0 for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Set current block number to 0 for node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost",
                        "Stream ended and restarted at block 0 for node localhost"));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockPersistenceFailed() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_PERSISTENCE_FAILED)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        "Received EndOfStream from block node localhost",
                        "Restarting stream at block 0 due to STREAM_ITEMS_PERSISTENCE_FAILED for node localhost",
                        "Ending stream and restarting at block 0 for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Set current block number to 0 for node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost",
                        "Stream ended and restarted at block 0 for node localhost"));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockSuccess() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        AtomicReference<Instant> startTime = new AtomicReference<>();

        return hapiTest(
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                waitUntilNextBlock().withBackgroundTraffic(true),
                doingContextual(spec -> startTime.set(Instant.now())),
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_SUCCESS)
                        .withBlockNumber(Long.MAX_VALUE)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                waitUntilNextBlock().withBackgroundTraffic(true),
                assertHgcaaLogContainsTimeframe(
                        byNodeId(0),
                        startTime::get,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        "Received EndOfStream from block node localhost",
                        "Stream ended successfully for node localhost",
                        "Request worker thread interrupted for node localhost",
                        "Closed connection to block node localhost",
                        "Establishing stream to block node localhost",
                        "Started request worker thread for block node localhost"));
    }
}
