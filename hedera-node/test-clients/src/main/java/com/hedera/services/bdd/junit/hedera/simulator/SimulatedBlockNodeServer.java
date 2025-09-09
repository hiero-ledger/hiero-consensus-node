// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.grpc.helidon.PbjRouting;
import com.hedera.pbj.grpc.helidon.config.PbjConfig;
import com.hedera.pbj.runtime.grpc.Pipeline;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.webserver.ConnectionConfig;
import io.helidon.webserver.WebServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.BlockStreamPublishServiceInterface;
import org.hiero.block.api.PublishStreamRequest;
import org.hiero.block.api.PublishStreamResponse;
import org.hiero.block.api.PublishStreamResponse.BlockAcknowledgement;
import org.hiero.block.api.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.PublishStreamResponse.ResendBlock;
import org.hiero.block.api.PublishStreamResponse.SkipBlock;

/**
 * A simulated block node server that implements the block streaming gRPC service.
 * This server can be configured to respond with different response codes and simulate
 * various error conditions for testing purposes.
 *
 * <p>Key capabilities include:
 * <ul>
 *   <li>Processing block headers and proofs from client streams</li>
 *   <li>Tracking verified blocks and maintaining last verified block number</li>
 *   <li>Sending various streaming responses (EndOfStream, SkipBlock, ResendBlock, BlockAcknowledgement)</li>
 *   <li>Handling duplicate block headers by sending SkipBlock responses</li>
 *   <li>Synchronizing block acknowledgments across multiple streams</li>
 *   <li>Supporting immediate response injection for testing error conditions</li>
 *   <li>Thread-safe tracking of block state using concurrent collections and locks</li>
 * </ul>
 *
 * <p>The simulator supports testing various block streaming scenarios including:
 * <ul>
 *   <li>Normal operation with sequential block processing</li>
 *   <li>Error handling with configurable end-of-stream responses</li>
 *   <li>Block resending and skipping scenarios</li>
 *   <li>Multiple concurrent client streams</li>
 *   <li>Proper synchronization of block acknowledgments across streams</li>
 * </ul>
 */
public class SimulatedBlockNodeServer {
    private static final Logger log = LogManager.getLogger(SimulatedBlockNodeServer.class);
    // Default values of the actual block node
    private static final int MAX_MESSAGE_SIZE_BYTES = 4_194_304; // 4 MBs
    private static final int BUFFER_SIZE = 32768;

    private final WebServer webServer;
    private final int port;
    private final MockBlockStreamServiceImpl serviceImpl;
    private final boolean highLatency;

    // Configuration for EndOfStream responses
    private final AtomicReference<EndOfStreamConfig> endOfStreamConfig = new AtomicReference<>();

    // Track the last verified block number (block number for which both header and proof are received)
    private final AtomicReference<Long> lastVerifiedBlockNumber = new AtomicReference<>(-1L); // Start at -1

    // Locks for synchronizing access to block tracking data structures
    private final ReadWriteLock blockTrackingLock = new ReentrantReadWriteLock();

    // Track all block numbers for which we have received proofs
    private final Set<Long> blocksWithProofs = ConcurrentHashMap.newKeySet();

    // Track all block numbers for which we have received headers but not yet proofs
    private final Set<Long> blocksWithHeadersOnly = ConcurrentHashMap.newKeySet();

    // Track which pipeline is currently streaming which block (block number -> pipeline)
    private final Map<Long, Pipeline<? super PublishStreamResponse>> streamingBlocks = new ConcurrentHashMap<>();

    // Track all active stream pipelines so we can send immediate responses or broadcast acknowledgements
    private final List<Pipeline<? super PublishStreamResponse>> activeStreams = new CopyOnWriteArrayList<>();

    private final Random random = new Random();
    private final Supplier<Long> externalLastVerifiedBlockNumberSupplier;

    private boolean hasEverBeenShutdown = false;

    private final AtomicBoolean sendingAcksEnabled = new AtomicBoolean(true);

    /**
     * Creates a new simulated block node server on the specified port.
     *
     * @param port the port to listen on
     * @param highLatency whether to simulate high-latency responses
     * @param lastVerifiedBlockNumberSupplier an optional supplier that provides the last verified block number
     * from an external source, can be null if not needed
     */
    public SimulatedBlockNodeServer(
            final int port, final boolean highLatency, @Nullable final Supplier<Long> lastVerifiedBlockNumberSupplier) {
        this.port = port;
        this.highLatency = highLatency;
        this.serviceImpl = new MockBlockStreamServiceImpl();
        this.externalLastVerifiedBlockNumberSupplier = lastVerifiedBlockNumberSupplier;

        final PbjConfig pbjConfig = PbjConfig.builder()
                .name("pbj")
                .maxMessageSizeBytes(MAX_MESSAGE_SIZE_BYTES)
                .build();
        final ConnectionConfig connectionConfig = ConnectionConfig.builder()
                .sendBufferSize(BUFFER_SIZE)
                .receiveBufferSize(BUFFER_SIZE)
                .build();

        this.webServer = WebServer.builder()
                .port(port)
                .addRouting(PbjRouting.builder().service(serviceImpl))
                .addProtocol(pbjConfig)
                .connectionConfig(connectionConfig)
                .build();
    }

    /**
     * Starts the server.
     *
     * @throws IOException if the server cannot be started
     */
    public void start() throws IOException {
        webServer.start();
        log.info("Simulated block node server started on port {}", port);
    }

    /**
     * Stops the server with a grace period for shutdown.
     * This method will wait up to 5 seconds for the server to terminate gracefully.
     * If interrupted, the current thread's interrupt flag will be set.
     */
    public void stop() {
        if (webServer != null) {
            try {

                webServer.stop();
                log.info("Simulated block node server on port {} stopped", port);
            } catch (final Exception e) {
                log.error("Error stopping simulated block node server on port {}", port, e);
            }
            this.hasEverBeenShutdown = true;
        }
    }

    /**
     * Gets the port this server is listening on.
     *
     * @return the port number this server is bound to
     */
    public int getPort() {
        return port;
    }

    public void setSendingBlockAcknowledgementsEnabled(final boolean sendingBlockAcksEnabled) {
        sendingAcksEnabled.set(sendingBlockAcksEnabled);
    }

    /**
     * Configure the server to respond with a specific EndOfStream response code
     * on the next block item.
     *
     * @param responseCode the response code to send, must not be null
     * @param blockNumber the block number to include in the response
     * @throws NullPointerException if responseCode is null
     */
    public void setEndOfStreamResponse(@NonNull final EndOfStream.Code responseCode, final long blockNumber) {
        requireNonNull(responseCode, "responseCode cannot be null");
        endOfStreamConfig.set(new EndOfStreamConfig(responseCode, blockNumber));
        log.info("Set EndOfStream response to {} for block {} on port {}", responseCode, blockNumber, port);
    }

    /**
     * Send an EndOfStream response immediately to all active streams.
     * This will end all active streams with the specified response code.
     *
     * @param responseCode the response code to send, must not be null
     * @param blockNumber the block number to include in the response
     * @return the last verified block number
     * @throws NullPointerException if responseCode is null
     */
    public long sendEndOfStreamImmediately(@NonNull final EndOfStream.Code responseCode, final long blockNumber) {
        requireNonNull(responseCode, "responseCode cannot be null");
        serviceImpl.sendEndOfStreamToAllStreams(responseCode, blockNumber);
        log.info(
                "Sent immediate EndOfStream response with code {} for block {} on port {}",
                responseCode,
                blockNumber,
                port);
        return lastVerifiedBlockNumber.get();
    }

    /**
     * Send a SkipBlock response immediately to all active streams.
     * This will instruct all active streams to skip the specified block.
     *
     * @param blockNumber the block number to skip
     */
    public void sendSkipBlockImmediately(final long blockNumber) {
        serviceImpl.sendSkipBlockToAllStreams(blockNumber);
        log.info("Sent immediate SkipBlock response for block {} on port {}", blockNumber, port);
    }

    /**
     * Send a ResendBlock response immediately to all active streams.
     * This will instruct all active streams to resend the specified block.
     *
     * @param blockNumber the block number to resend
     */
    public void sendResendBlockImmediately(final long blockNumber) {
        serviceImpl.sendResendBlockToAllStreams(blockNumber);
        log.info("Sent immediate ResendBlock response for block {} on port {}", blockNumber, port);
    }

    /**
     * Gets the last verified block number.
     *
     * @return the last verified block number, initially -1 if no blocks have been verified
     */
    public long getLastVerifiedBlockNumber() {
        return lastVerifiedBlockNumber.get();
    }

    /**
     * Checks if a specific block number has been fully received (header and proof) by this server.
     * This method is thread-safe and acquires a read lock to check the block status.
     *
     * @param blockNumber the block number to check
     * @return true if the block has been fully received, false otherwise
     */
    public boolean hasReceivedBlock(final long blockNumber) {
        blockTrackingLock.readLock().lock();
        try {
            // A block is considered received only if we have its proof
            return blocksWithProofs.contains(blockNumber);
        } finally {
            blockTrackingLock.readLock().unlock();
        }
    }

    /**
     * Gets all block numbers that have been fully received (header and proof) by this server.
     * This method is thread-safe and acquires a read lock to access the block collection.
     *
     * @return a new immutable set of all received block numbers
     */
    @NonNull
    public Set<Long> getReceivedBlockNumbers() {
        blockTrackingLock.readLock().lock();
        try {
            // Return only blocks for which we have proofs
            return Set.copyOf(blocksWithProofs);
        } finally {
            blockTrackingLock.readLock().unlock();
        }
    }

    /**
     * @return whether this server has ever been shutdown.
     */
    public boolean hasEverBeenShutdown() {
        return hasEverBeenShutdown;
    }

    /**
     * Reset all configured responses to default behavior.
     * This clears any configured EndOfStream responses.
     */
    public void resetResponses() {
        endOfStreamConfig.set(null);
        log.info("Reset all responses to default behavior on port {}", port);
    }

    /**
     * Configuration for EndOfStream responses.
     *
     * @param responseCode the EndOfStream response code to send, never null
     * @param blockNumber the block number to include in the response
     */
    private record EndOfStreamConfig(@NonNull EndOfStream.Code responseCode, long blockNumber) {
        /**
         * Creates a new EndOfStreamConfig with the specified response code and block number.
         *
         * @param responseCode the EndOfStream response code to send
         * @param blockNumber the block number to include in the response
         * @throws NullPointerException if responseCode is null
         */
        private EndOfStreamConfig {
            requireNonNull(responseCode, "Response code cannot be null");
        }
    }

    /**
     * Implementation of the BlockStreamService that can be configured to respond
     * with different response codes. This class handles the gRPC streaming interactions
     * with clients and manages block state tracking.
     */
    private class MockBlockStreamServiceImpl implements BlockStreamPublishServiceInterface {
        @Override
        public @NonNull Pipeline<? super org.hiero.block.api.PublishStreamRequest> publishBlockStream(
                @NonNull Pipeline<? super PublishStreamResponse> replies) {
            requireNonNull(replies, "replies cannot be null");

            // Add the new stream pipeline to the list of active streams
            // Acquire lock to ensure consistent view when adding to activeStreams
            blockTrackingLock.writeLock().lock();
            try {
                activeStreams.add(replies);
                log.info(
                        "New block stream connection established on port {}. Total streams: {}",
                        port,
                        activeStreams.size());
            } finally {
                blockTrackingLock.writeLock().unlock();
            }

            return new Pipeline<>() {
                // Track block number for this specific stream
                private volatile Long currentBlockNumber = null;

                @Override
                public void onNext(final PublishStreamRequest request) {
                    // Acquire lock once for the entire request processing
                    blockTrackingLock.writeLock().lock();
                    try {
                        // Move endOfStreamConfig check inside the lock for thread safety
                        final EndOfStreamConfig config = endOfStreamConfig.getAndSet(null);
                        if (config != null) {
                            sendEndOfStream(replies, config.responseCode(), config.blockNumber());
                            return;
                        }

                        if (request.hasEndStream()) {
                            log.debug("Received end of stream from stream {}", replies.hashCode());
                            serviceImpl.removeStreamFromTracking(replies);
                        } else if (request.hasBlockItems()) {
                            // Iterate through each BlockItem in the request
                            for (final BlockItem item : request.blockItems().blockItems()) {
                                if (item.hasBlockHeader()) {
                                    final var header = item.blockHeader();
                                    final long blockNumber = header.number();

                                    // We might want to catch up using a supplier from
                                    // another BN simulator
                                    if (externalLastVerifiedBlockNumberSupplier != null
                                            && externalLastVerifiedBlockNumberSupplier.get()
                                                            - lastVerifiedBlockNumber.get()
                                                    > 1) {
                                        lastVerifiedBlockNumber.set(externalLastVerifiedBlockNumberSupplier.get());
                                    }

                                    final long lastVerifiedBlockNum = lastVerifiedBlockNumber.get();
                                    if (blockNumber - lastVerifiedBlockNum > 1) {
                                        handleBehindResponse(replies, blockNumber, lastVerifiedBlockNum);
                                        return;
                                    }

                                    // Set the current block number being processed by THIS stream instance
                                    currentBlockNumber = blockNumber;
                                    log.info(
                                            "Received BlockHeader for block {} on port {} from stream {}",
                                            blockNumber,
                                            port,
                                            replies.hashCode());

                                    // Requirement 3: Check if block already exists (header AND proof received)
                                    if (blocksWithProofs.contains(blockNumber)) {
                                        log.warn(
                                                "Block {} already fully received (header+proof). Sending BlockAcknowledgement to stream {} on port {}.",
                                                blockNumber,
                                                replies.hashCode(),
                                                port);
                                        buildAndSendBlockAcknowledgement(blockNumber, replies);
                                        // Continue to the next BlockItem in the request
                                        continue;
                                    }

                                    // Requirement 1: Check if another stream is currently sending this block's parts
                                    if (streamingBlocks.containsKey(blockNumber)) {
                                        // If it's a different stream trying to send the same header
                                        if (streamingBlocks.get(blockNumber) != replies) {
                                            log.warn(
                                                    "Block {} header received from stream {}, but another stream ({}) is already sending parts. Sending SkipBlock to stream {} on port {}.",
                                                    blockNumber,
                                                    replies.hashCode(),
                                                    streamingBlocks
                                                            .get(blockNumber)
                                                            .hashCode(),
                                                    replies.hashCode(),
                                                    port);
                                            sendSkipBlock(replies, blockNumber);
                                            // Continue to the next BlockItem in the request
                                            continue;
                                        }
                                        // If it's the same stream sending the header again (e.g., duplicate header item
                                        // in
                                        // the same request)
                                        log.warn(
                                                "Block {} header received again from the same stream {} while streaming. Ignoring duplicate header item.",
                                                blockNumber,
                                                replies.hashCode());
                                        // Continue to the next BlockItem in the request
                                        continue;
                                    }

                                    // If block doesn't exist and no one else is streaming it, mark it as
                                    // header-received
                                    // and associate this stream with it.
                                    blocksWithHeadersOnly.add(blockNumber);
                                    streamingBlocks.put(blockNumber, replies);
                                    log.info(
                                            "Accepted BlockHeader for block {}. Stream {} is now sending parts on port {}.",
                                            blockNumber,
                                            replies.hashCode(),
                                            port);

                                } else if (item.hasBlockProof()) {
                                    final var proof = item.blockProof();
                                    final long blockNumber = proof.block();
                                    log.info(
                                            "Received BlockProof for block {} on port {} from stream {}",
                                            blockNumber,
                                            port,
                                            replies.hashCode());

                                    // Validate proof context
                                    if (currentBlockNumber == null
                                            || currentBlockNumber != blockNumber
                                            || !streamingBlocks.containsKey(blockNumber)
                                            || streamingBlocks.get(blockNumber) != replies) {
                                        log.error(
                                                "Received BlockProof for block {} from stream {} on port {}, but stream state is inconsistent (currentBlockNumber={}, expectedStream={}). Ignoring proof.",
                                                blockNumber,
                                                replies.hashCode(),
                                                port,
                                                currentBlockNumber,
                                                streamingBlocks.get(blockNumber) != null
                                                        ? streamingBlocks
                                                                .get(blockNumber)
                                                                .hashCode()
                                                        : "none");
                                        // Continue to the next BlockItem in the request
                                        continue;
                                    }

                                    // Mark block as fully received
                                    blocksWithHeadersOnly.remove(blockNumber);
                                    blocksWithProofs.add(blockNumber);
                                    streamingBlocks.remove(blockNumber); // No longer streaming this specific block

                                    // Update last verified block number atomically
                                    final long newLastVerified = lastVerifiedBlockNumber.updateAndGet(
                                            currentMax -> Math.max(currentMax, blockNumber));
                                    log.info(
                                            "Block {} fully received (header+proof) on port {} from stream {}. Last verified block updated to: {}",
                                            blockNumber,
                                            port,
                                            replies.hashCode(),
                                            newLastVerified);

                                    // Requirement 2: Send BlockAcknowledgement to ALL connected pipelines
                                    log.info(
                                            "Broadcasting BlockAcknowledgement for block {} to {} active streams on port {}",
                                            blockNumber,
                                            activeStreams.size(),
                                            port);
                                    for (final Pipeline<? super PublishStreamResponse> pipeline : activeStreams) {
                                        if (highLatency) {
                                            // If the simulator is set to be with high latency, delay acknowledgements
                                            // with 1500 ms (assuming CN considers 1000 ms delays as high latency)
                                            Thread.sleep(1500);
                                        }

                                        buildAndSendBlockAcknowledgement(blockNumber, pipeline);
                                    }

                                    // Reset currentBlockNumber for this stream, as it finished sending this block
                                    currentBlockNumber = null;
                                }
                            } // End of loop through BlockItems
                        }
                    } catch (InterruptedException e) {
                        log.warn("Interrupted while waiting for BlockAcknowledgement", e);
                    } finally {
                        blockTrackingLock.writeLock().unlock();
                    }
                }

                @Override
                public void onError(final Throwable t) {
                    log.error("Error in block stream on port {}: {}", port, t.getMessage(), t);
                    handleStreamError(replies);
                }

                @Override
                public void onComplete() {
                    log.info("Block stream completed on port {} for stream {}", port, replies.hashCode());
                    // Just remove the stream normally on completion, no resend needed.
                    removeStreamFromTracking(replies);
                }

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void clientEndStreamReceived() {
                    Pipeline.super.clientEndStreamReceived();
                }
            };
        }

        /**
         * Sends an EndOfStream response to all active streams.
         * This method will also complete and remove all streams after sending the response.
         *
         * @param responseCode the response code to send, must not be null
         * @param blockNumber the block number to include
         * @throws NullPointerException if responseCode is null
         */
        public void sendEndOfStreamToAllStreams(@NonNull final EndOfStream.Code responseCode, final long blockNumber) {
            requireNonNull(responseCode, "responseCode cannot be null");
            log.info(
                    "Sending EndOfStream ({}, block {}) to {} active streams on port {}",
                    responseCode,
                    blockNumber,
                    activeStreams.size(),
                    port);
            blockTrackingLock.writeLock().lock(); // Lock needed to safely iterate and modify activeStreams potentially
            try {
                final List<Pipeline<? super PublishStreamResponse>> streamsToRemove = new ArrayList<>();
                for (final Pipeline<? super PublishStreamResponse> pipeline : activeStreams) {
                    try {
                        sendEndOfStream(pipeline, responseCode, blockNumber);
                        // Assuming EndOfStream terminates the connection from server side perspective
                        streamsToRemove.add(pipeline); // Mark for removal after iteration
                    } catch (final Exception e) {
                        log.error("Failed to send EndOfStream to stream {} on port {}", pipeline.hashCode(), port, e);
                        streamsToRemove.add(pipeline); // Remove problematic stream
                    }
                }
                // Clean up streams that received EndOfStream or caused errors
                streamsToRemove.forEach(this::removeStreamFromTrackingInternal);
            } finally {
                blockTrackingLock.writeLock().unlock();
            }
        }

        /**
         * Sends a SkipBlock response to all active streams.
         * This instructs all clients to skip processing the specified block.
         *
         * @param blockNumber the block number to skip
         */
        public void sendSkipBlockToAllStreams(final long blockNumber) {
            log.info(
                    "Sending SkipBlock for block {} to {} active streams on port {}",
                    blockNumber,
                    activeStreams.size(),
                    port);
            // Use lock for consistent locking strategy with other methods
            blockTrackingLock.readLock().lock(); // Read lock is sufficient for iteration
            try {
                for (final Pipeline<? super PublishStreamResponse> pipeline : activeStreams) {
                    try {
                        sendSkipBlock(pipeline, blockNumber);
                    } catch (final Exception e) {
                        log.error("Failed to send SkipBlock to stream {} on port {}", pipeline.hashCode(), port, e);
                        // Decide if we should remove the stream on failure
                        // removeStreamFromTracking(pipeline);
                    }
                }
            } finally {
                blockTrackingLock.readLock().unlock();
            }
        }

        /**
         * Sends a ResendBlock response to all active streams.
         * This instructs all clients to resend the specified block.
         *
         * @param blockNumber the block number to resend
         */
        public void sendResendBlockToAllStreams(final long blockNumber) {
            log.info(
                    "Sending ResendBlock for block {} to {} active streams on port {}",
                    blockNumber,
                    activeStreams.size(),
                    port);
            // Use lock for consistent locking strategy with other methods
            blockTrackingLock.readLock().lock(); // Read lock is sufficient for iteration
            try {
                for (final Pipeline<? super PublishStreamResponse> pipeline : activeStreams) {
                    try {
                        sendResendBlock(pipeline, blockNumber);
                    } catch (final Exception e) {
                        log.error("Failed to send ResendBlock to stream {} on port {}", pipeline.hashCode(), port, e);
                        // Decide if we should remove the stream on failure
                        // removeStreamFromTracking(pipeline);
                    }
                }
            } finally {
                blockTrackingLock.readLock().unlock();
            }
        }

        // Helper methods for sending specific responses

        /**
         * Sends an EndOfStream response to a specific pipeline.
         *
         * @param pipeline the pipeline to send the response to, must not be null
         * @param responseCode the response code to send, must not be null
         * @param blockNumber the block number to include in the response
         * @throws NullPointerException if pipeline or responseCode is null
         */
        private void sendEndOfStream(
                @NonNull final Pipeline<? super PublishStreamResponse> pipeline,
                @NonNull final EndOfStream.Code responseCode,
                final long blockNumber) {
            requireNonNull(pipeline, "pipeline cannot be null");
            requireNonNull(responseCode, "responseCode cannot be null");

            final EndOfStream endOfStream = EndOfStream.newBuilder()
                    .status(responseCode)
                    .blockNumber(blockNumber)
                    .build();
            final PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().endStream(endOfStream).build();
            pipeline.onNext(response);
            log.debug(
                    "Sent EndOfStream ({}, block {}) to stream {} on port {}",
                    responseCode,
                    blockNumber, // blockNumber from config is potentially confusing here, using lastVerified is safer
                    pipeline.hashCode(),
                    port);
        }

        /**
         * Sends a SkipBlock response to a specific pipeline.
         *
         * @param pipeline the pipeline to send the response to, must not be null
         * @param blockNumber the block number to skip
         * @throws NullPointerException if pipeline is null
         */
        private void sendSkipBlock(
                @NonNull final Pipeline<? super PublishStreamResponse> pipeline, final long blockNumber) {
            requireNonNull(pipeline, "pipeline cannot be null");
            final SkipBlock skipBlock =
                    SkipBlock.newBuilder().blockNumber(blockNumber).build();
            final PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().skipBlock(skipBlock).build();
            pipeline.onNext(response);
            log.debug("Sent SkipBlock for block {} to stream {} on port {}", blockNumber, pipeline.hashCode(), port);
        }

        /**
         * Sends a ResendBlock response to a specific pipeline.
         *
         * @param pipeline the pipeline to send the response to, must not be null
         * @param blockNumber the block number to resend
         * @throws NullPointerException if pipeline is null
         */
        private void sendResendBlock(
                @NonNull final Pipeline<? super PublishStreamResponse> pipeline, final long blockNumber) {
            requireNonNull(pipeline, "pipeline cannot be null");
            final ResendBlock resendBlock =
                    ResendBlock.newBuilder().blockNumber(blockNumber).build();
            final PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().resendBlock(resendBlock).build();
            pipeline.onNext(response);
            log.debug("Sent ResendBlock for block {} to stream {} on port {}", blockNumber, pipeline.hashCode(), port);
        }

        /**
         * Handles sending a BEHIND response to a client when the block number is more than 1 ahead of the last verified block.
         * This indicates that the client is ahead of the server and should restart streaming from an earlier block.
         *
         * @param pipeline The pipeline to send the response to, must not be null
         * @param blockNumber The block number that was requested
         * @param lastVerifiedBlockNum The last verified block number
         * @throws NullPointerException if pipeline is null
         */
        private void handleBehindResponse(
                @NonNull final Pipeline<? super PublishStreamResponse> pipeline,
                final long blockNumber,
                final long lastVerifiedBlockNum) {
            requireNonNull(pipeline, "pipeline cannot be null");

            final EndOfStream eos = EndOfStream.newBuilder()
                    .blockNumber(lastVerifiedBlockNum)
                    .status(EndOfStream.Code.BEHIND)
                    .build();
            final PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().endStream(eos).build();

            try {
                pipeline.onNext(response);
                log.debug(
                        "Sent EndOfStream BEHIND for block {} to stream {} on port {}. Last verified: {}",
                        blockNumber,
                        pipeline.hashCode(),
                        port,
                        lastVerifiedBlockNum);
            } catch (final Exception e) {
                log.error(
                        "Failed to send EndOfStream BEHIND for block {} to stream {} on port {}. Removing stream.",
                        blockNumber,
                        pipeline.hashCode(),
                        port,
                        e);
                // Clean up the stream on error
                serviceImpl.removeStreamFromTracking(pipeline);
            }
        }

        /**
         * Removes a stream pipeline from active tracking and cleans up any associated state.
         * Acquires the necessary write lock to ensure thread safety.
         *
         * @param pipeline The pipeline to remove.
         * @throws NullPointerException if pipeline is null
         */
        private void removeStreamFromTracking(@NonNull final Pipeline<? super PublishStreamResponse> pipeline) {
            requireNonNull(pipeline, "pipeline cannot be null");
            blockTrackingLock.writeLock().lock();
            try {
                removeStreamFromTrackingInternal(pipeline);
            } finally {
                blockTrackingLock.writeLock().unlock();
            }
        }

        /**
         * Internal helper to remove stream pipeline state. MUST be called while holding the write lock.
         * This method removes the pipeline from active streams and cleans up any blocks that were being streamed.
         *
         * @param pipeline The pipeline to remove, must not be null
         * @throws NullPointerException if pipeline is null
         */
        private void removeStreamFromTrackingInternal(@NonNull final Pipeline<? super PublishStreamResponse> pipeline) {
            requireNonNull(pipeline, "pipeline cannot be null");

            if (activeStreams.remove(pipeline)) {
                log.info(
                        "Removed stream pipeline {} from active list on port {}. Remaining: {}",
                        pipeline.hashCode(),
                        port,
                        activeStreams.size());
            }
            // Check if this stream was actively sending a block and remove it from tracking
            streamingBlocks.entrySet().removeIf(entry -> {
                if (entry.getValue() == pipeline) {
                    final long blockNumber = entry.getKey();
                    log.warn(
                            "Stream {} disconnected while sending block {}. Removing from streaming state on port {}.",
                            pipeline.hashCode(),
                            blockNumber,
                            port);
                    // Also remove from headers-only set, as we won't get a proof now
                    blocksWithHeadersOnly.remove(blockNumber);
                    return true;
                }
                return false;
            });
        }

        /**
         * Handles cleanup and potential resend logic when a stream encounters an error.
         * This method attempts to find another stream to request a resend of the block that was being processed.
         *
         * @param erroredPipeline The pipeline that encountered the error.
         * @throws NullPointerException if erroredPipeline is null
         */
        private void handleStreamError(@NonNull final Pipeline<? super PublishStreamResponse> erroredPipeline) {
            requireNonNull(erroredPipeline, "erroredPipeline cannot be null");

            Long blockNumberOnError = null;
            // Find if this pipeline was streaming a block
            blockTrackingLock.readLock().lock(); // Read lock sufficient to check streamingBlocks
            try {
                final Optional<Map.Entry<Long, Pipeline<? super PublishStreamResponse>>> entry =
                        streamingBlocks.entrySet().stream()
                                .filter(e -> e.getValue() == erroredPipeline)
                                .findFirst();
                if (entry.isPresent()) {
                    blockNumberOnError = entry.get().getKey();
                    log.warn(
                            "Stream {} encountered an error while streaming block {} on port {}. Attempting to request resend.",
                            erroredPipeline.hashCode(),
                            blockNumberOnError,
                            port);
                }
            } finally {
                blockTrackingLock.readLock().unlock();
            }

            // Perform cleanup *after* checking state and potentially initiating resend
            removeStreamFromTracking(erroredPipeline);

            // If an error occurred *while* this stream was sending block parts
            if (blockNumberOnError != null) {
                // Find other active streams
                final List<Pipeline<? super PublishStreamResponse>> otherStreams;
                // Use lock for consistent locking strategy when accessing activeStreams
                blockTrackingLock.readLock().lock();
                try {
                    otherStreams = activeStreams.stream()
                            .filter(s -> s != erroredPipeline)
                            .toList();
                } finally {
                    blockTrackingLock.readLock().unlock();
                }

                if (!otherStreams.isEmpty()) {
                    // Select a random stream from the others
                    final Pipeline<? super PublishStreamResponse> chosenStream =
                            otherStreams.get(random.nextInt(otherStreams.size()));
                    log.info(
                            "Requesting resend of block {} from randomly chosen stream {} on port {}.",
                            blockNumberOnError,
                            chosenStream.hashCode(),
                            port);
                    try {
                        sendResendBlock(chosenStream, blockNumberOnError);
                    } catch (final Exception e) {
                        log.error(
                                "Failed to send ResendBlock for block {} to stream {} on port {}.",
                                blockNumberOnError,
                                chosenStream.hashCode(),
                                port,
                                e);
                        // Consider removing the chosenStream as well if sending fails
                        // removeStreamFromTracking(chosenStream);
                    }
                } else {
                    log.warn(
                            "Error occurred for block {} on stream {}, but no other active streams available to request resend on port {}.",
                            blockNumberOnError,
                            erroredPipeline.hashCode(),
                            port);
                }
            }
        }

        @Override
        public @NonNull String serviceName() {
            return BlockStreamPublishServiceInterface.super.serviceName();
        }

        @Override
        public @NonNull String fullName() {
            return BlockStreamPublishServiceInterface.super.fullName();
        }

        @Override
        public @NonNull List<Method> methods() {
            return BlockStreamPublishServiceInterface.super.methods();
        }

        @Override
        public @NonNull Pipeline<? super Bytes> open(
                @NonNull ServiceInterface.Method method,
                @NonNull ServiceInterface.RequestOptions options,
                @NonNull Pipeline<? super Bytes> replies) {
            return BlockStreamPublishServiceInterface.super.open(method, options, replies);
        }
    }

    /**
     * This method acknowledges receipt of a block and indicates whether the block was already processed.
     * If the acknowledgment cannot be sent, the stream is removed from tracking.
     *
     * @param blockNumber The block number being acknowledged
     * @param pipeline The pipeline to send the acknowledgment to, must not be null
     *
     * @throws NullPointerException if pipeline is null
     */
    private void buildAndSendBlockAcknowledgement(
            final long blockNumber, @NonNull final Pipeline<? super PublishStreamResponse> pipeline) {
        requireNonNull(pipeline, "pipeline cannot be null");

        if (!sendingAcksEnabled.get()) {
            return;
        }

        final BlockAcknowledgement ack =
                BlockAcknowledgement.newBuilder().blockNumber(blockNumber).build();
        final PublishStreamResponse response =
                PublishStreamResponse.newBuilder().acknowledgement(ack).build();
        try {
            pipeline.onNext(response);
            log.debug(
                    "Sent BlockAcknowledgement for block {} (exists={}) to stream {} on port {}. Last verified: {}",
                    blockNumber,
                    pipeline.hashCode(),
                    port,
                    lastVerifiedBlockNumber.get());
        } catch (final Exception e) {
            log.error(
                    "Failed to send BlockAcknowledgement for block {} to stream {} on port {}. Removing stream.",
                    blockNumber,
                    pipeline.hashCode(),
                    port,
                    e);
            // If we can't send an ack, the stream is likely broken. Remove it.
            serviceImpl.removeStreamFromTracking(pipeline);
        }
    }
}
