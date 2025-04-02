// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Utility verbs for interacting with block node simulators.
 */
public class BlockNodeSimulatorVerbs {
    private BlockNodeSimulatorVerbs() {
        // Utility class
    }

    /**
     * Creates a builder for interacting with a specific block node simulator.
     * This is a convenience method that uses a more fluent naming convention.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @return a builder for the operation targeting the specified simulator
     */
    public static BlockNodeSimulatorBuilder blockNodeSimulator(int nodeIndex) {
        return new BlockNodeSimulatorBuilder(nodeIndex);
    }

    /**
     * Creates a builder for operations that affect all block node simulators.
     *
     * @return a builder for operations affecting all simulators
     */
    public static AllBlockNodeSimulatorBuilder allBlockNodeSimulators() {
        return new AllBlockNodeSimulatorBuilder();
    }

    /**
     * Builder for block node simulator operations targeting a specific simulator.
     */
    public static class BlockNodeSimulatorBuilder {
        private final int nodeIndex;

        /**
         * Creates a new builder for the specified simulator index.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         */
        public BlockNodeSimulatorBuilder(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Sends an immediate EndOfStream response to the block node simulator.
         *
         * @param responseCode the response code to send
         * @return a builder for configuring the operation
         */
        public BlockNodeSimulatorOp.SendEndOfStreamBuilder sendEndOfStreamImmediately(
                PublishStreamResponseCode responseCode) {
            return BlockNodeSimulatorOp.sendEndOfStreamImmediately(nodeIndex, responseCode);
        }

        /**
         * Sends an immediate SkipBlock response to the block node simulator.
         *
         * @param blockNumber the block number to skip
         * @return the operation
         */
        public BlockNodeSimulatorOp sendSkipBlockImmediately(long blockNumber) {
            return BlockNodeSimulatorOp.sendSkipBlockImmediately(nodeIndex, blockNumber)
                    .build();
        }

        /**
         * Sends an immediate ResendBlock response to the block node simulator.
         *
         * @param blockNumber the block number to resend
         * @return the operation
         */
        public BlockNodeSimulatorOp sendResendBlockImmediately(long blockNumber) {
            return BlockNodeSimulatorOp.sendResendBlockImmediately(nodeIndex, blockNumber)
                    .build();
        }

        /**
         * Shuts down the block node simulator immediately.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp shutDownImmediately() {
            return BlockNodeSimulatorOp.shutdownImmediately(nodeIndex).build();
        }

        /**
         * Starts the block node simulator immediately.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp startImmediately() {
            return BlockNodeSimulatorOp.startImmediately(nodeIndex).build();
        }

        /**
         * Asserts that a specific block has been received by the block node simulator.
         *
         * @param blockNumber the block number to check
         * @return the operation
         */
        public BlockNodeSimulatorOp assertBlockReceived(long blockNumber) {
            return BlockNodeSimulatorOp.assertBlockReceived(nodeIndex, blockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from the block node simulator.
         *
         * @return a builder for configuring the operation
         */
        public BlockNodeSimulatorOp.GetLastVerifiedBlockBuilder getLastVerifiedBlock() {
            return BlockNodeSimulatorOp.getLastVerifiedBlock(nodeIndex);
        }

        /**
         * Creates a builder for sending an immediate EndOfStream response with a specific block number.
         *
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         * @return the operation
         */
        public BlockNodeSimulatorOp sendEndOfStreamWithBlock(PublishStreamResponseCode responseCode, long blockNumber) {
            return BlockNodeSimulatorOp.sendEndOfStreamImmediately(nodeIndex, responseCode)
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
        public BlockNodeSimulatorOp sendEndOfStreamWithBlock(
                PublishStreamResponseCode responseCode, long blockNumber, AtomicLong lastVerifiedBlockNumber) {
            return BlockNodeSimulatorOp.sendEndOfStreamImmediately(nodeIndex, responseCode)
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
        public BlockNodeSimulatorOp getLastVerifiedBlockExposing(AtomicLong lastVerifiedBlockNumber) {
            return BlockNodeSimulatorOp.getLastVerifiedBlock(nodeIndex)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from the block node simulator and exposes it through a Consumer.
         *
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return the operation
         */
        public BlockNodeSimulatorOp getLastVerifiedBlockExposing(Consumer<Long> lastVerifiedBlockConsumer) {
            return BlockNodeSimulatorOp.getLastVerifiedBlock(nodeIndex)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockConsumer)
                    .build();
        }
    }

    /**
     * Builder for operations that affect all block node simulators.
     */
    public static class AllBlockNodeSimulatorBuilder {
        /**
         * Shuts down all block node simulators immediately.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp shutDownAll() {
            return BlockNodeSimulatorOp.shutdownAll().build();
        }

        /**
         * Starts all previously shutdown block node simulators.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp startAll() {
            return BlockNodeSimulatorOp.startAll().build();
        }
    }
}
