// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.simulator.BlockNodeController;
import com.hedera.services.bdd.spec.HapiSpec;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;

/**
 * A utility operation for interacting with the block node in action. The block node type is dependent
 * on the {@link BlockNodeMode} configured on a per test basis. Currently, it supports simulated block nodes and
 * real block nodes running in Docker containers.
 */
public class BlockNodeOp extends UtilOp {
    private static final Logger log = LogManager.getLogger(BlockNodeOp.class);

    private final long nodeIndex;
    private final BlockNodeAction action;
    private final EndOfStream.Code responseCode;
    private final long blockNumber;
    private final AtomicLong lastVerifiedBlockNumber;
    private final Consumer<Long> lastVerifiedBlockConsumer;
    private final boolean sendBlockAcknowledgementsEnabled;
    private final boolean persistState;

    private BlockNodeOp(
            final long nodeIndex,
            final BlockNodeAction action,
            final EndOfStream.Code responseCode,
            final long blockNumber,
            final AtomicLong lastVerifiedBlockNumber,
            final Consumer<Long> lastVerifiedBlockConsumer,
            final boolean sendBlockAcknowledgementsEnabled,
            final boolean persistState) {
        this.nodeIndex = nodeIndex;
        this.action = action;
        this.responseCode = responseCode;
        this.blockNumber = blockNumber;
        this.lastVerifiedBlockNumber = lastVerifiedBlockNumber;
        this.lastVerifiedBlockConsumer = lastVerifiedBlockConsumer;
        this.sendBlockAcknowledgementsEnabled = sendBlockAcknowledgementsEnabled;
        this.persistState = persistState;
    }

    @Override
    protected boolean submitOp(final HapiSpec spec) throws Throwable {
        final BlockNodeMode mode =
                HapiSpec.TARGET_BLOCK_NODE_NETWORK.get().getBlockNodeModeById().get(nodeIndex);
        if (mode == BlockNodeMode.SIMULATOR) {
            return submitSimulatorOp();
        } else if (mode == BlockNodeMode.REAL) {
            return submitContainerOp();
        } else if (mode == BlockNodeMode.LOCAL_NODE) {
            log.error("Block node operations are not supported in local node mode for node {}", nodeIndex);
            return false;
        } else {
            throw new IllegalStateException("Node " + nodeIndex + " is not a block node");
        }
    }

    private boolean submitSimulatorOp() {
        final BlockNodeController controller =
                HapiSpec.TARGET_BLOCK_NODE_NETWORK.get().getBlockNodeController();
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
            case SEND_SKIP_BLOCK_IMMEDIATELY:
                controller.sendSkipBlockImmediately(nodeIndex, blockNumber);
                verifiedBlock = controller.getLastVerifiedBlockNumber(nodeIndex);
                log.info(
                        "Sent immediate SkipBlock response for block {} on simulator {}, last verified block: {}",
                        blockNumber,
                        nodeIndex,
                        verifiedBlock);
                break;
            case SEND_RESEND_BLOCK_IMMEDIATELY:
                controller.sendResendBlockImmediately(nodeIndex, blockNumber);
                verifiedBlock = controller.getLastVerifiedBlockNumber(nodeIndex);
                log.info(
                        "Sent immediate ResendBlock response for block {} on simulator {}, last verified block: {}",
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
            case SHUTDOWN:
                controller.shutdownSimulator(nodeIndex, persistState);
                log.info("Shutdown simulator {}", nodeIndex);
                break;
            case START:
                if (!controller.isBlockNodeShutdown(nodeIndex)) {
                    log.error("Cannot start simulator {} because it has not been shut down", nodeIndex);
                    return false;
                }
                try {
                    controller.startSimulator(nodeIndex);
                    log.info("Started simulator {}", nodeIndex);
                } catch (final IOException e) {
                    log.error("Failed to start simulator {}", nodeIndex, e);
                    return false;
                }
                break;
            case SHUTDOWN_ALL:
                controller.shutdownAllSimulators(persistState);
                log.info("Shutdown all simulators to simulate connection drops");
                break;
            case START_ALL:
                if (!controller.areAnyBlockNodesBeenShutdown()) {
                    log.error("Cannot start simulators because none have been shut down");
                    return false;
                }
                try {
                    controller.startAllSimulators();
                    log.info("Started all previously shutdown simulators");
                } catch (final IOException e) {
                    log.error("Failed to start simulators", e);
                    return false;
                }
                break;
            case ASSERT_BLOCK_RECEIVED:
                final boolean received = controller.hasReceivedBlock(nodeIndex, blockNumber);
                if (!received) {
                    final String errorMsg = String.format(
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
            case UPDATE_SENDING_BLOCK_ACKS:
                log.info(
                        "[node {}] Update sending block acknowledgements to: {}",
                        nodeIndex,
                        sendBlockAcknowledgementsEnabled);
                controller.setSendBlockAcknowledgementsEnabled(nodeIndex, sendBlockAcknowledgementsEnabled);
                break;
            default:
                throw new IllegalStateException("Action: " + action + " is not supported for block node simulators");
        }

        if (lastVerifiedBlockNumber != null) {
            lastVerifiedBlockNumber.set(verifiedBlock);
        }

        if (lastVerifiedBlockConsumer != null) {
            lastVerifiedBlockConsumer.accept(verifiedBlock);
        }
        return true;
    }

    private boolean submitContainerOp() {
        final BlockNodeController controller =
                HapiSpec.TARGET_BLOCK_NODE_NETWORK.get().getBlockNodeController();

        switch (action) {
            case START:
                if (!controller.isBlockNodeShutdown(nodeIndex)) {
                    log.error("Cannot start container {} because it has not been shut down", nodeIndex);
                    return false;
                }
                controller.startContainer(nodeIndex);
                break;
            case SHUTDOWN:
                controller.shutdownContainer(nodeIndex, persistState);
                break;
            default:
                throw new IllegalStateException("Action: " + action + " is not supported for block node containers");
        }
        return true;
    }

    /**
     * Enum defining the possible actions to perform on a block node.
     */
    public enum BlockNodeAction {
        /** Start block node */
        START,
        /** Start all block nodes */
        START_ALL,
        /** Shutdown block node */
        SHUTDOWN,
        /** Shutdown all block nodes */
        SHUTDOWN_ALL,

        /* Next actions are only applicable to simulated block nodes */

        /** Send {@link PublishStreamResponse.EndOfStream} response */
        SEND_END_OF_STREAM_IMMEDIATELY,
        /** Send {@link PublishStreamResponse.SkipBlock} response */
        SEND_SKIP_BLOCK_IMMEDIATELY,
        /** Send {@link PublishStreamResponse.ResendBlock} response */
        SEND_RESEND_BLOCK_IMMEDIATELY,
        /** Set {@link PublishStreamResponse.EndOfStream} response */
        SET_END_OF_STREAM_RESPONSE,
        /** Reset all responses to default behavior */
        RESET_RESPONSES,
        /** Assert that a specific block has been received */
        ASSERT_BLOCK_RECEIVED,
        /** Get the last verified block number */
        GET_LAST_VERIFIED_BLOCK,
        /** Whether or not to send block acknowledgements */
        UPDATE_SENDING_BLOCK_ACKS
    }

    /**
     * Creates a builder for sending an immediate {@link PublishStreamResponse.EndOfStream} response to a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @param responseCode the response code to send
     * @return a builder for the operation
     */
    public static SendEndOfStreamBuilder sendEndOfStreamImmediately(
            final long nodeIndex, final EndOfStream.Code responseCode) {
        return new SendEndOfStreamBuilder(nodeIndex, responseCode);
    }

    /**
     * Creates a builder for sending an immediate {@link PublishStreamResponse.SkipBlock} response to a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @param blockNumber the block number to skip
     * @return a builder for the operation
     */
    public static SendSkipBlockBuilder sendSkipBlockImmediately(final long nodeIndex, final long blockNumber) {
        return new SendSkipBlockBuilder(nodeIndex, blockNumber);
    }

    /**
     * Creates a builder for sending an immediate {@link PublishStreamResponse.ResendBlock} response to a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @param blockNumber the block number to resend
     * @return a builder for the operation
     */
    public static SendResendBlockBuilder sendResendBlockImmediately(final long nodeIndex, final long blockNumber) {
        return new SendResendBlockBuilder(nodeIndex, blockNumber);
    }

    /**
     * Creates a builder for shutting down a specific block node immediately.
     *
     * @param nodeIndex the index of the block node (0-based)
     * @return a builder for the operation
     */
    public static ShutdownBuilder shutdownImmediately(final long nodeIndex) {
        return new ShutdownBuilder(nodeIndex);
    }

    /**
     * Creates a builder for shutting down all block nodes immediately.
     *
     * @return a builder for the operation
     */
    public static ShutdownAllBuilder shutdownAll() {
        return new ShutdownAllBuilder();
    }

    /**
     * Creates a builder for starting a specific block node immediately.
     *
     * @param nodeIndex the index of the block node (0-based)
     * @return a builder for the operation
     */
    public static StartBuilder startImmediately(final long nodeIndex) {
        return new StartBuilder(nodeIndex);
    }

    /**
     * Creates a builder for starting all previously shutdown block nodes.
     *
     * @return a builder for the operation
     */
    public static StartAllBuilder startAll() {
        return new StartAllBuilder();
    }

    /**
     * Creates a builder for asserting that a specific block has been received by a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @param blockNumber the block number to check
     * @return a builder for the operation
     */
    public static AssertBlockReceivedBuilder assertBlockReceived(final long nodeIndex, final long blockNumber) {
        return new AssertBlockReceivedBuilder(nodeIndex, blockNumber);
    }

    /**
     * Creates a builder for getting the last verified block number from a block node simulator.
     *
     * @param nodeIndex the index of the block node simulator (0-based)
     * @return a builder for the operation
     */
    public static GetLastVerifiedBlockBuilder getLastVerifiedBlock(final long nodeIndex) {
        return new GetLastVerifiedBlockBuilder(nodeIndex);
    }

    /**
     * Creates a builder that allows for updating whether block acknowledgements will be sent by the block node simulator.
     *
     * @param nodeIndex the index of the block node simulator to update (0-based)
     * @param sendBlockAcknowledgementsEnabled true if block acknowledgements will be sent, otherwise they will not
     * @return the builder
     */
    public static UpdateSendingBlockAcknowledgementsBuilder updateSendingBlockAcknowledgements(
            final long nodeIndex, final boolean sendBlockAcknowledgementsEnabled) {
        return new UpdateSendingBlockAcknowledgementsBuilder(nodeIndex, sendBlockAcknowledgementsEnabled);
    }

    /**
     * Builder for sending an immediate EndOfStream response to a block node simulator.
     * This builder also implements UtilOp so it can be used directly in HapiSpec without calling build().
     */
    public static class SendEndOfStreamBuilder extends UtilOp {
        private final long nodeIndex;
        private final EndOfStream.Code responseCode;
        private long blockNumber = 0;
        private AtomicLong lastVerifiedBlockNumber;
        private Consumer<Long> lastVerifiedBlockConsumer;

        private SendEndOfStreamBuilder(final long nodeIndex, final EndOfStream.Code responseCode) {
            this.nodeIndex = nodeIndex;
            this.responseCode = responseCode;
        }

        /**
         * Sets the block number to include in the response.
         *
         * @param blockNumber the block number
         * @return this builder
         */
        public SendEndOfStreamBuilder withBlockNumber(final long blockNumber) {
            this.blockNumber = blockNumber;
            return this;
        }

        /**
         * Exposes the last verified block number through an AtomicLong.
         *
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return this builder
         */
        public SendEndOfStreamBuilder exposingLastVerifiedBlockNumber(final AtomicLong lastVerifiedBlockNumber) {
            this.lastVerifiedBlockNumber = lastVerifiedBlockNumber;
            return this;
        }

        /**
         * Exposes the last verified block number through a Consumer.
         *
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return this builder
         */
        public SendEndOfStreamBuilder exposingLastVerifiedBlockNumber(final Consumer<Long> lastVerifiedBlockConsumer) {
            this.lastVerifiedBlockConsumer = lastVerifiedBlockConsumer;
            return this;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(
                    nodeIndex,
                    BlockNodeAction.SEND_END_OF_STREAM_IMMEDIATELY,
                    responseCode,
                    blockNumber,
                    lastVerifiedBlockNumber,
                    lastVerifiedBlockConsumer,
                    true,
                    true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for sending an immediate SkipBlock response to a block node simulator.
     * This builder also implements UtilOp so it can be used directly in HapiSpec without calling build().
     */
    public static class SendSkipBlockBuilder extends UtilOp {
        private final long nodeIndex;
        private final long blockNumber;

        private SendSkipBlockBuilder(final long nodeIndex, final long blockNumber) {
            this.nodeIndex = nodeIndex;
            this.blockNumber = blockNumber;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(
                    nodeIndex, BlockNodeAction.SEND_SKIP_BLOCK_IMMEDIATELY, null, blockNumber, null, null, true, true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    /**
     * Builder for sending an immediate ResendBlock response to a block node simulator.
     * This builder also implements UtilOp so it can be used directly in HapiSpec without calling build().
     */
    public static class SendResendBlockBuilder extends UtilOp {
        private final long nodeIndex;
        private final long blockNumber;

        private SendResendBlockBuilder(final long nodeIndex, final long blockNumber) {
            this.nodeIndex = nodeIndex;
            this.blockNumber = blockNumber;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(
                    nodeIndex,
                    BlockNodeAction.SEND_RESEND_BLOCK_IMMEDIATELY,
                    null,
                    blockNumber,
                    null,
                    null,
                    true,
                    true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class ShutdownBuilder extends UtilOp {
        private final long nodeIndex;
        private boolean persistState = true;

        private ShutdownBuilder(final long nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Sets whether to persist the state of the block node before shutting down.
         * Default is true.
         * @param persistState whether to persist the state of the block node before shutting down
         */
        public ShutdownBuilder persistState(final boolean persistState) {
            this.persistState = persistState;
            return this;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(nodeIndex, BlockNodeAction.SHUTDOWN, null, 0, null, null, true, persistState);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class ShutdownAllBuilder extends UtilOp {
        private boolean persistState = true;

        /**
         * Sets whether the state should be persistent after shutdown.
         *
         * @param persistState whether state should be persistent
         * @return this builder
         */
        public ShutdownAllBuilder persistState(final boolean persistState) {
            this.persistState = persistState;
            return this;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(0, BlockNodeAction.SHUTDOWN_ALL, null, 0, null, null, true, persistState);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class StartBuilder extends UtilOp {
        private final long nodeIndex;

        private StartBuilder(final long nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(nodeIndex, BlockNodeAction.START, null, 0, null, null, true, true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class StartAllBuilder extends UtilOp {
        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(0, BlockNodeAction.START_ALL, null, 0, null, null, true, true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class AssertBlockReceivedBuilder extends UtilOp {
        private final long nodeIndex;
        private final long blockNumber;

        AssertBlockReceivedBuilder(final long nodeIndex, final long blockNumber) {
            this.nodeIndex = nodeIndex;
            this.blockNumber = blockNumber;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(
                    nodeIndex, BlockNodeAction.ASSERT_BLOCK_RECEIVED, null, blockNumber, null, null, true, true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class UpdateSendingBlockAcknowledgementsBuilder extends UtilOp {
        private final long nodeIndex;
        private final boolean sendBlockAcknowledgementsEnabled;

        private UpdateSendingBlockAcknowledgementsBuilder(
                final long nodeIndex, final boolean sendBlockAcknowledgementsEnabled) {
            this.nodeIndex = nodeIndex;
            this.sendBlockAcknowledgementsEnabled = sendBlockAcknowledgementsEnabled;
        }

        public BlockNodeOp build() {
            return new BlockNodeOp(
                    nodeIndex,
                    BlockNodeAction.UPDATE_SENDING_BLOCK_ACKS,
                    null,
                    0,
                    null,
                    null,
                    sendBlockAcknowledgementsEnabled,
                    true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }

    public static class GetLastVerifiedBlockBuilder extends UtilOp {
        private final long nodeIndex;
        private AtomicLong lastVerifiedBlockNumber;
        private Consumer<Long> lastVerifiedBlockConsumer;

        GetLastVerifiedBlockBuilder(final long nodeIndex) {
            this.nodeIndex = nodeIndex;
        }

        /**
         * Exposes the last verified block number through an AtomicLong.
         *
         * @param lastVerifiedBlockNumber the AtomicLong to store the last verified block number
         * @return this builder
         */
        public GetLastVerifiedBlockBuilder exposingLastVerifiedBlockNumber(final AtomicLong lastVerifiedBlockNumber) {
            this.lastVerifiedBlockNumber = lastVerifiedBlockNumber;
            return this;
        }

        /**
         * Exposes the last verified block number through a Consumer.
         *
         * @param lastVerifiedBlockConsumer the consumer to receive the last verified block number
         * @return this builder
         */
        public GetLastVerifiedBlockBuilder exposingLastVerifiedBlockNumber(
                final Consumer<Long> lastVerifiedBlockConsumer) {
            this.lastVerifiedBlockConsumer = lastVerifiedBlockConsumer;
            return this;
        }

        /**
         * Builds the operation.
         *
         * @return the operation
         */
        public BlockNodeOp build() {
            return new BlockNodeOp(
                    nodeIndex,
                    BlockNodeAction.GET_LAST_VERIFIED_BLOCK,
                    null,
                    0,
                    lastVerifiedBlockNumber,
                    lastVerifiedBlockConsumer,
                    true,
                    true);
        }

        @Override
        protected boolean submitOp(final HapiSpec spec) throws Throwable {
            return build().submitOp(spec);
        }
    }
}
