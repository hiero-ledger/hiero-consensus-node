// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.services.bdd.junit.hedera.containers.BlockNodeContainer;
import com.hedera.services.bdd.spec.HapiSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BlockNodeContainerOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(BlockNodeContainerOp.class);

    private final long nodeIndex;
    private BlockNodeContainerAction action;

    /**
     * Enum defining the possible actions to perform on a block node container.
     */
    public enum BlockNodeContainerAction {
        START_CONTAINER,
        SHUTDOWN_CONTAINER,
        START_ALL_CONTAINERS,
        SHUTDOWN_ALL_CONTAINERS,
    }

    public BlockNodeContainerOp(long nodeIndex, final BlockNodeContainerAction action) {
        this.nodeIndex = nodeIndex;
        this.action = action;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        final var blockNodeContainerMap =
                HapiSpec.TARGET_BLOCK_NODE_NETWORK.get().getBlockNodeContainerById();
        final BlockNodeContainer blockNodeContainer = blockNodeContainerMap.get(nodeIndex);

        switch (action) {
            case START_CONTAINER:
                blockNodeContainer.start();
                // Wait for container to be fully ready and network to stabilize
                Thread.sleep(5000);
                log.info("Started container {} and waited for readiness", nodeIndex);
                blockNodeContainerMap.put(nodeIndex, blockNodeContainer);
                break;
            case SHUTDOWN_CONTAINER:
                log.info("Shutting down container {}", nodeIndex);
                blockNodeContainer.stop();
                // Wait for container to fully stop and network to recognize it
                Thread.sleep(3000);
                log.info("Container {} shutdown complete", nodeIndex);
                blockNodeContainerMap.remove(nodeIndex, blockNodeContainer);
                break;
            default:
                throw new IllegalStateException("Unknown action: " + action);
        }

        return true;
    }
}
