// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE_SIMULATOR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.BlockNodeSimulatorVerbs.blockNodeSimulator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlock;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnection;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.BeforeAll;
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
    final Stream<DynamicTest> waitForMultipleBlocksWithBackgroundTraffic() {
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
    final Stream<DynamicTest> node0BlockNodeInternalError() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);
        return hapiTest(
                // Block node simulator 0 respond with internal error
                blockNodeSimulator(0)
                        .sendEndOfStreamImmediately(PublishStreamResponseCode.STREAM_ITEMS_INTERNAL_ERROR)
                        .withBlockNumber(123456L)
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber),
                assertHgcaaLogContains(
                        byNodeId(0), "Error returned from block node at block number 123456", Duration.ofSeconds(5)));
    }

    @HapiTest
    final Stream<DynamicTest> node0BlockNodeShutsDownAndRestarts() {
        return hapiTest(
                // Shut down block node simulator 0
                blockNodeSimulator(0).shutDownImmediately(),
                // Verify the log message in node 0's log
                assertHgcaaLogContains(byNodeId(0), "Error in block node stream", Duration.ofSeconds(10)),
                // Restart node 0
                blockNodeSimulator(0).restartImmediately(),
                sleepFor(10000));
        // TODO Add more log assertions for reconnection
    }

    @HapiTest
    final Stream<DynamicTest> assertGenesisBlockReceivedBySimulator() {
        return hapiTest(blockNodeSimulator(0).assertBlockReceived(0));
    }

    @HapiTest
    final Stream<DynamicTest> getLastVerifiedBlockFromSimulator() {
        AtomicLong lastVerifiedBlockNumber = new AtomicLong(0);

        return hapiTest(
                // Create a crypto account to generate some blocks
                cryptoCreate("account1")
                        .balance(ONE_HUNDRED_HBARS)
                        .declinedReward(true)
                        .stakedNodeId(0),
                // Wait a bit to ensure the block is processed
                sleepFor(2000),
                // Get the last verified block number using the fluent API
                blockNodeSimulator(0)
                        .getLastVerifiedBlock()
                        .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber)
                        .build(),
                // Assert that the block has been received by the simulator
                blockNodeSimulator(0).assertBlockReceived(lastVerifiedBlockNumber.get()));
    }
}
