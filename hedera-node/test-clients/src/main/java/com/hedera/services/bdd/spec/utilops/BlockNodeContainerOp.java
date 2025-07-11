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
        final var shutdownContainerPorts =
                HapiSpec.TARGET_BLOCK_NODE_NETWORK.get().getShutdownContainerPorts();

        switch (action) {
            case START_CONTAINER:
                if (!shutdownContainerPorts.containsKey(nodeIndex)) {
                    log.error("Cannot start simulator {} because it has not been shut down", nodeIndex);
                    return false;
                }

                try {
                    final int port = shutdownContainerPorts.get(nodeIndex);
                    final BlockNodeContainer blockNodeContainer = new BlockNodeContainer(nodeIndex, port);

                    blockNodeContainer.start();

                    log.info("Started container {} and waited for readiness", nodeIndex);
                    blockNodeContainerMap.put(nodeIndex, blockNodeContainer);
                    shutdownContainerPorts.remove(nodeIndex);
                } catch (final Exception e) {
                    log.error("Failed to start container {}", nodeIndex, e);
                    return false;
                }
                break;
            case SHUTDOWN_CONTAINER:
                log.info("Shutting down container {}", nodeIndex);
                final BlockNodeContainer shutdownContainer = blockNodeContainerMap.get(nodeIndex);

                shutdownContainerPorts.put(nodeIndex, shutdownContainer.getPort());
                shutdownContainer.stop();

                log.info("Container {} shutdown complete", nodeIndex);
                blockNodeContainerMap.remove(nodeIndex, shutdownContainer);
                break;
            default:
                throw new IllegalStateException("Unknown action: " + action);
        }

        return true;
    }
}
