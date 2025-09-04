// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;

/**
 * Utility verbs for interacting with block node simulators.
 */
public class BlockNodeVerbs {
    private BlockNodeVerbs() {
        // Utility class
    }

    /**
     * Creates a builder for interacting with a specific block node simulator.
     * This is a convenience method that uses a more fluent naming convention.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @return a builder for the operation targeting the specified simulator
     */
    public static BlockNodeBuilder blockNode(long nodeIndex) {
        return new BlockNodeBuilder(nodeIndex);
    }

    /**
     * Creates a builder for operations that affect all block node simulators.
     *
     * @return a builder for operations affecting all simulators
     */
    public static AllBlockNodeBuilder allBlockNodeSimulators() {
        return new AllBlockNodeBuilder();
    }

    /**
     * Builder for block node simulator operations targeting a specific simulator.
     */
    public static class BlockNodeBuilder {
        private final long nodeIndex;

        /**
         * Creates a new builder for the specified simulator index.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         */
        public BlockNodeBuilder(long nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Sends an immediate EndOfStream response to the block node simulator.
         *
         * @param responseCode the response code to send
         * @return a builder for configuring the operation
         */
        public BlockNodeOp.SendEndOfStreamBuilder sendEndOfStreamImmediately(EndOfStream.Code responseCode) {
            return BlockNodeOp.sendEndOfStreamImmediately(nodeIndex, responseCode);
        }

        /**
         * Sends an immediate SkipBlock response to the block node simulator.
         *
         * @param blockNumber the block number to skip
         * @return the operation
         */
        public BlockNodeOp sendSkipBlockImmediately(long blockNumber) {
            return BlockNodeOp.sendSkipBlockImmediately(nodeIndex, blockNumber).build();
        }

        /**
         * Sends an immediate ResendBlock response to the block node simulator.
         *
         * @param blockNumber the block number to resend
         * @return the operation
         */
        public BlockNodeOp sendResendBlockImmediately(long blockNumber) {
            return BlockNodeOp.sendResendBlockImmediately(nodeIndex, blockNumber)
                    .build();
        }

        /**
         * Shuts down the block node simulator immediately.
         *
         * @return the operation
         */
        public BlockNodeOp.ShutdownBuilder shutDownImmediately() {
            return BlockNodeOp.shutdownImmediately(nodeIndex);
        }

        /**
         * Starts the block node simulator immediately.
         *
         * @return the operation
         */
        public BlockNodeOp startImmediately() {
            return BlockNodeOp.startImmediately(nodeIndex).build();
        }

        /**
         * Asserts that a specific block has been received by the block node simulator.
         *
         * @param blockNumber the block number to check
         * @return the operation
         */
        public BlockNodeOp assertBlockReceived(long blockNumber) {
            return BlockNodeOp.assertBlockReceived(nodeIndex, blockNumber).build();
        }

        /**
         * Gets the last verified block number from the block node simulator.
         *
         * @return a builder for configuring the operation
         */
        public BlockNodeOp.GetLastVerifiedBlockBuilder getLastVerifiedBlock() {
            return BlockNodeOp.getLastVerifiedBlock(nodeIndex);
        }

        /**
         * Creates a builder for sending an immediate EndOfStream response with a specific block number.
         *
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         * @return the operation
         */
        public BlockNodeOp sendEndOfStreamWithBlock(EndOfStream.Code responseCode, long blockNumber) {
            return BlockNodeOp.sendEndOfStreamImmediately(nodeIndex, responseCode)
                    .withBlockNumber(blockNumber)
                    .build();
        }

        /**
         * Creates a builder for sending an immediate EndOfStream response with a specific block number
         * and exposing the last verified block number.
         *
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return the operation
         */
        public BlockNodeOp sendEndOfStreamWithBlock(
                EndOfStream.Code responseCode, long blockNumber, AtomicLong lastVerifiedBlockNumber) {
            return BlockNodeOp.sendEndOfStreamImmediately(nodeIndex, responseCode)
                    .withBlockNumber(blockNumber)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from the block node simulator and exposes it through an AtomicLong.
         *
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return the operation
         */
        public BlockNodeOp getLastVerifiedBlockExposing(AtomicLong lastVerifiedBlockNumber) {
            return BlockNodeOp.getLastVerifiedBlock(nodeIndex)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from the block node simulator and exposes it through a Consumer.
         *
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return the operation
         */
        public BlockNodeOp getLastVerifiedBlockExposing(Consumer<Long> lastVerifiedBlockConsumer) {
            return BlockNodeOp.getLastVerifiedBlock(nodeIndex)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockConsumer)
                    .build();
        }

        /**
         * Updates whether block acknowledgements should be sent by the block node simulator.
         *
         * @param sendBlockAcknowledgementsEnabled true if acknowledgements should be sent, else they will not be sent
         * @return the operation
         */
        public BlockNodeOp updateSendingBlockAcknowledgements(final boolean sendBlockAcknowledgementsEnabled) {
            return BlockNodeOp.updateSendingBlockAcknowledgements(nodeIndex, sendBlockAcknowledgementsEnabled)
                    .build();
        }
    }

    /**
     * Builder for operations that affect all block node simulators.
     */
    public static class AllBlockNodeBuilder {
        /**
         * Shuts down all block node simulators immediately.
         *
         * @return the operation
         */
        public BlockNodeOp shutDownAll() {
            return BlockNodeOp.shutdownAll().build();
        }

        /**
         * Starts all previously shutdown block node simulators.
         *
         * @return the operation
         */
        public BlockNodeOp startAll() {
            return BlockNodeOp.startAll().build();
        }
    }
}
