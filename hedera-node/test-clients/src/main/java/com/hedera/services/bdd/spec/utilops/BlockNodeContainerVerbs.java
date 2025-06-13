// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

public class BlockNodeContainerVerbs {

    private BlockNodeContainerVerbs() {
        // Utility class
    }

    /**
     * Creates a builder for operations that target a specific block node container.
     *
     * @param nodeIndex the ID of the block node container
     * @return a builder for the operation targeting the specified container
     */
    public static BlockNodeContainerBuilder blockNodeContainer(int nodeIndex) {
        return new BlockNodeContainerBuilder(nodeIndex);
    }

    /**
     * Builder for block node simulator operations targeting a specific simulator.
     */
    public static class BlockNodeContainerBuilder {
        private final int nodeIndex;

        /**
         * Creates a new builder for the specified simulator index.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         */
        public BlockNodeContainerBuilder(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Starts the block node simulator immediately.
         *
         * @return the operation
         */
        public BlockNodeContainerOp startImmediately() {
            return new BlockNodeContainerOp(nodeIndex, BlockNodeContainerOp.BlockNodeContainerAction.START_CONTAINER);
        }

        /**
         * Shuts down the block node simulator immediately.
         *
         * @return the operation
         */
        public BlockNodeContainerOp shutDownImmediately() {
            return new BlockNodeContainerOp(
                    nodeIndex, BlockNodeContainerOp.BlockNodeContainerAction.SHUTDOWN_CONTAINER);
        }
    }
}
