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
     * Creates a builder for interacting with block node simulators.
     * This is a convenience method that uses a more fluent naming convention.
     *
     * @return a builder for the operation
     */
    public static BlockNodeSimulatorBuilder blockNodeSimulator() {
        return new BlockNodeSimulatorBuilder();
    }

    /**
     * Builder for block node simulator operations.
     */
    public static class BlockNodeSimulatorBuilder {
        /**
         * Sends an immediate EndOfStream response to a block node simulator.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @param responseCode the response code to send
         * @return a builder for configuring the operation
         */
        public BlockNodeSimulatorOp.SendEndOfStreamBuilder sendEndOfStreamImmediately(
                int nodeIndex, PublishStreamResponseCode responseCode) {
            return BlockNodeSimulatorOp.sendEndOfStreamImmediately(nodeIndex, responseCode);
        }

        /**
         * Shuts down a specific block node simulator immediately.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @return the operation
         */
        public BlockNodeSimulatorOp shutDownImmediately(int nodeIndex) {
            return BlockNodeSimulatorOp.shutdownImmediately(nodeIndex).build();
        }

        /**
         * Shuts down all block node simulators immediately.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp shutDownAll() {
            return BlockNodeSimulatorOp.shutdownAll().build();
        }

        /**
         * Restarts a specific block node simulator immediately.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @return the operation
         */
        public BlockNodeSimulatorOp restartImmediately(int nodeIndex) {
            return BlockNodeSimulatorOp.restartImmediately(nodeIndex).build();
        }

        /**
         * Restarts all previously shutdown block node simulators.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp restartAll() {
            return BlockNodeSimulatorOp.restartAll().build();
        }

        /**
         * Asserts that a specific block has been received by a block node simulator.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @param blockNumber the block number to check
         * @return the operation
         */
        public BlockNodeSimulatorOp assertBlockReceived(int nodeIndex, long blockNumber) {
            return BlockNodeSimulatorOp.assertBlockReceived(nodeIndex, blockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from a block node simulator.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @return a builder for configuring the operation
         */
        public BlockNodeSimulatorOp.GetLastVerifiedBlockBuilder getLastVerifiedBlock(int nodeIndex) {
            return BlockNodeSimulatorOp.getLastVerifiedBlock(nodeIndex);
        }

        /**
         * Creates a builder for sending an immediate EndOfStream response with a specific block number.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         * @return the operation
         */
        public BlockNodeSimulatorOp sendEndOfStreamWithBlock(
                int nodeIndex, PublishStreamResponseCode responseCode, long blockNumber) {
            return BlockNodeSimulatorOp.sendEndOfStreamImmediately(nodeIndex, responseCode)
                    .withBlockNumber(blockNumber)
                    .build();
        }

        /**
         * Creates a builder for sending an immediate EndOfStream response with a specific block number
         * and exposing the last verified block number.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return the operation
         */
        public BlockNodeSimulatorOp sendEndOfStreamWithBlock(
                int nodeIndex,
                PublishStreamResponseCode responseCode,
                long blockNumber,
                AtomicLong lastVerifiedBlockNumber) {
            return BlockNodeSimulatorOp.sendEndOfStreamImmediately(nodeIndex, responseCode)
                    .withBlockNumber(blockNumber)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from a block node simulator and exposes it through an AtomicLong.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return the operation
         */
        public BlockNodeSimulatorOp getLastVerifiedBlockExposing(int nodeIndex, AtomicLong lastVerifiedBlockNumber) {
            return BlockNodeSimulatorOp.getLastVerifiedBlock(nodeIndex)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockNumber)
                    .build();
        }

        /**
         * Gets the last verified block number from a block node simulator and exposes it through a Consumer.
         *
         * @param nodeIndex the index of the block node simulator (0-based)
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return the operation
         */
        public BlockNodeSimulatorOp getLastVerifiedBlockExposing(
                int nodeIndex, Consumer<Long> lastVerifiedBlockConsumer) {
            return BlockNodeSimulatorOp.getLastVerifiedBlock(nodeIndex)
                    .exposingLastVerifiedBlockNumber(lastVerifiedBlockConsumer)
                    .build();
        }
    }
}
