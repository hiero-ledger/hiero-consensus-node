package com.hedera.services.bdd.suites.blocknode;

import static com.hedera.services.bdd.junit.TestTags.BLOCK_NODE_SIMULATOR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.BlockNodeSimulatorVerbs.blockNodeSimulator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContains;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForAny;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilNextBlocks;

import com.hedera.services.bdd.HapiBlockNode;
import com.hedera.services.bdd.HapiBlockNode.BlockNodeConfig;
import com.hedera.services.bdd.HapiBlockNode.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import java.time.Duration;
import java.util.stream.Stream;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(BLOCK_NODE_SIMULATOR)
public class BlockBufferBackpressureTest {

    private static final int NODE_0_ID = 0;
    
    @HapiTest
    @HapiBlockNode(
            networkSize = 1,
            blockNodeConfigs = {@BlockNodeConfig(nodeId = NODE_0_ID, mode = BlockNodeMode.SIMULATOR)},
            subProcessNodeConfigs = {
                    @SubProcessNodeConfig(
                            nodeId = NODE_0_ID,
                            blockNodeIds = {NODE_0_ID},
                            blockNodePriorities = {NODE_0_ID})
            })
    final Stream<DynamicTest> testBlockBufferBackpressure() {
        return hapiTest(
                waitUntilNextBlocks(15).withBackgroundTraffic(true),
                // wait until the logs show the buffer has 15 blocks in it
                assertHgcaaLogContains(byNodeId(NODE_0_ID), "blocksChecked=15", Duration.ofSeconds(30)),
                // shutdown the block node
                blockNodeSimulator(NODE_0_ID).shutDownImmediately(),
                // now that the block node has been shutdown, the buffer should start filling up
                // now let's wait until the buffer is full
                assertHgcaaLogContains(byNodeId(NODE_0_ID), "!!! Block buffer is saturated", Duration.ofMinutes(2)),
                // the buffer is now full and transactions are being rejected... as a result the consensus node
                // should go into a CHECKING state
                waitForAny(byNodeId(NODE_0_ID), Duration.ofMinutes(2), PlatformStatus.CHECKING),
                // restart the block node, this should clear the buffer
                blockNodeSimulator(NODE_0_ID).startImmediately(),
                // wait for the platform to go back to an ACTIVE state
                waitForAny(byNodeId(NODE_0_ID), Duration.ofMinutes(2), PlatformStatus.ACTIVE),
                // wait for some more blocks
                waitUntilNextBlocks(15).withBackgroundTraffic(true));
    }
}
