// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.upgrade;

import static com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK;

import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;

/**
 * Adds the node with "classic" metadata implied by the given node id and refreshes the {@link SubProcessNetwork} roster.
 */
public class AddNodeOp extends UtilOp {
    private final long nodeId;

    public AddNodeOp(final long nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalStateException("Can only add nodes to a SubProcessNetwork");
        }

        if (BlockNodeMode.SIMULATOR.equals(subProcessNetwork.getBlockNodeMode())) {
            BlockNodeNetwork blockNodeNetwork = SHARED_BLOCK_NODE_NETWORK.get();
            blockNodeNetwork.getBlockNodeModeById().put(nodeId, BlockNodeMode.SIMULATOR);
            blockNodeNetwork.getBlockNodeIdsBySubProcessNodeId().put(nodeId, new long[] {nodeId});
            blockNodeNetwork.getBlockNodePrioritiesBySubProcessNodeId().put(nodeId, new long[] {0});

            // Get latest verified block number from block node 0
            final SimulatedBlockNodeServer blockNode0 = blockNodeNetwork.getSimulatedBlockNodeById().entrySet().stream()
                    .findFirst()
                    .get()
                    .getValue();
            blockNodeNetwork.addSimulatorNode(nodeId, blockNode0::getLastVerifiedBlockNumber);
            SHARED_BLOCK_NODE_NETWORK.set(blockNodeNetwork);
        }

        subProcessNetwork.addNode(nodeId);
        return false;
    }
}
