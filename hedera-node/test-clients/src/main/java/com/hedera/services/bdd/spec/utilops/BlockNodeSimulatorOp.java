// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.simulator.BlockNodeSimulatorController;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A utility operation for interacting with the block node simulator.
 */
public class BlockNodeSimulatorOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(BlockNodeSimulatorOp.class);

    private final int nodeIndex;
    private final BlockNodeSimulatorAction action;
    private final PublishStreamResponseCode responseCode;
    private final long blockNumber;
    private final AtomicLong lastVerifiedBlockNumber;
    private final Consumer<Long> lastVerifiedBlockConsumer;

    private BlockNodeSimulatorOp(
            int nodeIndex,
            BlockNodeSimulatorAction action,
            PublishStreamResponseCode responseCode,
            long blockNumber,
            AtomicLong lastVerifiedBlockNumber,
            Consumer<Long> lastVerifiedBlockConsumer) {
        this.nodeIndex = nodeIndex;
        this.action = action;
        this.responseCode = responseCode;
        this.blockNumber = blockNumber;
        this.lastVerifiedBlockNumber = lastVerifiedBlockNumber;
        this.lastVerifiedBlockConsumer = lastVerifiedBlockConsumer;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork network)) {
            throw new IllegalStateException("Block node simulator operations require a SubProcessNetwork");
        }

        // Check if block node mode is set to SIMULATOR
        if (network.getBlockNodeMode() != BlockNodeMode.SIMULATOR) {
            throw new IllegalStateException(
                    "Block node simulator operations require BlockNodeMode.SIMULATOR to be set. " + "Current mode: "
                            + network.getBlockNodeMode() + ". "
                            + "Set system property 'hapi.spec.blocknode.mode=SIM' to enable simulator mode.");
        }

        BlockNodeSimulatorController controller = network.getBlockNodeSimulatorController();
        long verifiedBlock = 0;

        switch (action) {
            case SEND_END_OF_STREAM_IMMEDIATELY:
                verifiedBlock = controller.sendEndOfStreamImmediately(nodeIndex, responseCode, blockNumber);
                log.info(
                        "Sent immediate EndOfStream response with code {} for block {} on simulator {}, last verified block: {}",
                        responseCode,
                        blockNumber,
                        nodeIndex,
                        verifiedBlock);
                break;
            case SET_END_OF_STREAM_RESPONSE:
                controller.setEndOfStreamResponse(nodeIndex, responseCode, blockNumber);
                verifiedBlock = controller.getLastVerifiedBlockNumber(nodeIndex);
                log.info(
                        "Set EndOfStream response code {} for block {} on simulator {}, last verified block: {}",
                        responseCode,
                        blockNumber,
                        nodeIndex,
                        verifiedBlock);
                break;
            case RESET_RESPONSES:
                controller.resetResponses(nodeIndex);
                verifiedBlock = controller.getLastVerifiedBlockNumber(nodeIndex);
                log.info("Reset all responses on simulator {} to default behavior", nodeIndex);
                break;
            case SHUTDOWN_SIMULATOR:
                controller.shutdownSimulator(nodeIndex);
                log.info("Shutdown simulator {} to simulate connection drop", nodeIndex);
                break;
            case RESTART_SIMULATOR:
                if (!controller.isSimulatorShutdown(nodeIndex)) {
                    log.error("Cannot restart simulator {} because it has not been shut down", nodeIndex);
                    return false;
                }
                try {
                    controller.restartSimulator(nodeIndex);
                    log.info("Restarted simulator {}", nodeIndex);
                } catch (IOException e) {
                    log.error("Failed to restart simulator {}", nodeIndex, e);
                    return false;
                }
                break;
            case SHUTDOWN_ALL_SIMULATORS:
                controller.shutdownAllSimulators();
                log.info("Shutdown all simulators to simulate connection drops");
                break;
            case RESTART_ALL_SIMULATORS:
                if (!controller.areAnySimulatorsShutdown()) {
                    log.error("Cannot restart simulators because none have been shut down");
                    return false;
                }
                try {
                    controller.restartAllSimulators();
                    log.info("Restarted all previously shutdown simulators");
                } catch (IOException e) {
                    log.error("Failed to restart simulators", e);
                    return false;
                }
                break;
            case ASSERT_BLOCK_RECEIVED:
                boolean received = controller.hasReceivedBlock(nodeIndex, blockNumber);
                if (!received) {
                    String errorMsg = String.format(
                            "Block %d has not been received by simulator %d. Received blocks: %s",
                            blockNumber, nodeIndex, controller.getReceivedBlockNumbers(nodeIndex));
                    log.error(errorMsg);
                    throw new AssertionError(errorMsg);
                }
                log.info(
                        "Successfully verified that block {} has been received by simulator {}",
                        blockNumber,
                        nodeIndex);
                break;
            case GET_LAST_VERIFIED_BLOCK:
                verifiedBlock = controller.getLastVerifiedBlockNumber(nodeIndex);
                log.info("Retrieved last verified block number {} from simulator {}", verifiedBlock, nodeIndex);
                break;
        }

        if (lastVerifiedBlockNumber != null) {
            lastVerifiedBlockNumber.set(verifiedBlock);
        }

        if (lastVerifiedBlockConsumer != null) {
            lastVerifiedBlockConsumer.accept(verifiedBlock);
        }

        return true;
    }

    /**
     * Enum defining the possible actions to perform on a block node simulator.
     */
    public enum BlockNodeSimulatorAction {
        SEND_END_OF_STREAM_IMMEDIATELY,
        SET_END_OF_STREAM_RESPONSE,
        RESET_RESPONSES,
        SHUTDOWN_SIMULATOR,
        RESTART_SIMULATOR,
        SHUTDOWN_ALL_SIMULATORS,
        RESTART_ALL_SIMULATORS,
        ASSERT_BLOCK_RECEIVED,
        GET_LAST_VERIFIED_BLOCK
    }

    /**
     * Creates a builder for sending an immediate EndOfStream response to a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @param responseCode the response code to send
     * @return a builder for the operation
     */
    public static SendEndOfStreamBuilder sendEndOfStreamImmediately(
            int nodeIndex, PublishStreamResponseCode responseCode) {
        return new SendEndOfStreamBuilder(nodeIndex, responseCode);
    }

    /**
     * Creates a builder for shutting down a specific block node simulator immediately.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @return a builder for the operation
     */
    public static ShutdownBuilder shutdownImmediately(int nodeIndex) {
        return new ShutdownBuilder(nodeIndex);
    }

    /**
     * Creates a builder for shutting down all block node simulators immediately.
     *
     * @return a builder for the operation
     */
    public static ShutdownAllBuilder shutdownAll() {
        return new ShutdownAllBuilder();
    }

    /**
     * Creates a builder for restarting a specific block node simulator immediately.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @return a builder for the operation
     */
    public static RestartBuilder restartImmediately(int nodeIndex) {
        return new RestartBuilder(nodeIndex);
    }

    /**
     * Creates a builder for restarting all previously shutdown block node simulators.
     *
     * @return a builder for the operation
     */
    public static RestartAllBuilder restartAll() {
        return new RestartAllBuilder();
    }

    /**
     * Creates a builder for asserting that a specific block has been received by a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @param blockNumber the block number to check
     * @return a builder for the operation
     */
    public static AssertBlockReceivedBuilder assertBlockReceived(int nodeIndex, long blockNumber) {
        return new AssertBlockReceivedBuilder(nodeIndex, blockNumber);
    }

    /**
     * Creates a builder for getting the last verified block number from a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @return a builder for the operation
     */
    public static GetLastVerifiedBlockBuilder getLastVerifiedBlock(int nodeIndex) {
        return new GetLastVerifiedBlockBuilder(nodeIndex);
    }

    /**
     * Builder for sending an immediate EndOfStream response to a block node simulator.
     * This builder also implements UtilOp so it can be used directly in HapiSpec without calling build().
     */
    public static class SendEndOfStreamBuilder extends UtilOp {
        private final int nodeIndex;
        private final PublishStreamResponseCode responseCode;
        private long blockNumber = 0;
        private AtomicLong lastVerifiedBlockNumber;
        private Consumer<Long> lastVerifiedBlockConsumer;

        private SendEndOfStreamBuilder(int nodeIndex, PublishStreamResponseCode responseCode) {
            this.nodeIndex = nodeIndex;
            this.responseCode = responseCode;
        }

        /**
         * Sets the block number to include in the EndOfStream response.
         *
         * @param blockNumber the block number
         * @return this builder
         */
        public SendEndOfStreamBuilder withBlockNumber(long blockNumber) {
            this.blockNumber = blockNumber;
            return this;
        }

        /**
         * Sets an AtomicLong to store the last verified block number.
         *
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return this builder
         */
        public SendEndOfStreamBuilder exposingLastVerifiedBlockNumber(AtomicLong lastVerifiedBlockNumber) {
            this.lastVerifiedBlockNumber = lastVerifiedBlockNumber;
            return this;
        }

        /**
         * Sets a consumer to receive the last verified block number.
         *
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return this builder
         */
        public SendEndOfStreamBuilder exposingLastVerifiedBlockNumber(Consumer<Long> lastVerifiedBlockConsumer) {
            this.lastVerifiedBlockConsumer = lastVerifiedBlockConsumer;
            return this;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(
                    nodeIndex,
                    BlockNodeSimulatorAction.SEND_END_OF_STREAM_IMMEDIATELY,
                    responseCode,
                    blockNumber,
                    lastVerifiedBlockNumber,
                    lastVerifiedBlockConsumer);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for shutting down a specific block node simulator.
     */
    public static class ShutdownBuilder extends UtilOp {
        private final int nodeIndex;

        private ShutdownBuilder(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(
                    nodeIndex, BlockNodeSimulatorAction.SHUTDOWN_SIMULATOR, null, 0, null, null);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for shutting down all block node simulators.
     */
    public static class ShutdownAllBuilder extends UtilOp {
        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(-1, BlockNodeSimulatorAction.SHUTDOWN_ALL_SIMULATORS, null, 0, null, null);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for restarting a specific block node simulator.
     */
    public static class RestartBuilder extends UtilOp {
        private final int nodeIndex;

        private RestartBuilder(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(nodeIndex, BlockNodeSimulatorAction.RESTART_SIMULATOR, null, 0, null, null);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for restarting all previously shutdown block node simulators.
     */
    public static class RestartAllBuilder extends UtilOp {
        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(-1, BlockNodeSimulatorAction.RESTART_ALL_SIMULATORS, null, 0, null, null);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for asserting that a specific block has been received by a block node simulator.
     */
    public static class AssertBlockReceivedBuilder extends UtilOp {
        private final int nodeIndex;
        private final long blockNumber;

        AssertBlockReceivedBuilder(int nodeIndex, long blockNumber) {
            this.nodeIndex = nodeIndex;
            this.blockNumber = blockNumber;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(
                    nodeIndex, BlockNodeSimulatorAction.ASSERT_BLOCK_RECEIVED, null, blockNumber, null, null);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for getting the last verified block number from a block node simulator.
     */
    public static class GetLastVerifiedBlockBuilder extends UtilOp {
        private final int nodeIndex;
        private AtomicLong lastVerifiedBlockNumber;
        private Consumer<Long> lastVerifiedBlockConsumer;

        GetLastVerifiedBlockBuilder(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Sets an AtomicLong to store the last verified block number.
         *
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return this builder
         */
        public GetLastVerifiedBlockBuilder exposingLastVerifiedBlockNumber(AtomicLong lastVerifiedBlockNumber) {
            this.lastVerifiedBlockNumber = lastVerifiedBlockNumber;
            return this;
        }

        /**
         * Sets a consumer to receive the last verified block number.
         *
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return this builder
         */
        public GetLastVerifiedBlockBuilder exposingLastVerifiedBlockNumber(Consumer<Long> lastVerifiedBlockConsumer) {
            this.lastVerifiedBlockConsumer = lastVerifiedBlockConsumer;
            return this;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeSimulatorOp build() {
            return new BlockNodeSimulatorOp(
                    nodeIndex,
                    BlockNodeSimulatorAction.GET_LAST_VERIFIED_BLOCK,
                    null,
                    0,
                    lastVerifiedBlockNumber,
                    lastVerifiedBlockConsumer);
        }

        @Override
        protected boolean submitOp(HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }
}
