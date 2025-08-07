// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import com.hedera.hapi.block.stream.protoc.BlockItem;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.block.api.protoc.BlockStreamPublishServiceGrpc;
import org.hiero.block.api.protoc.PublishStreamRequest;
import org.hiero.block.api.protoc.PublishStreamResponse;
import org.hiero.block.api.protoc.PublishStreamResponse.EndOfStream;
import org.hiero.block.api.protoc.PublishStreamResponse.ResendBlock;

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

    private final Server server;
    private final int port;
    private final MockBlockStreamServiceImpl serviceImpl;

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

    // Track which observer is currently streaming which block (block number -> observer)
    private final Map<Long, StreamObserver<PublishStreamResponse>> streamingBlocks = new ConcurrentHashMap<>();

    // Track all active stream observers so we can send immediate responses or broadcast acknowledgements
    private final List<StreamObserver<PublishStreamResponse>> activeStreams = new CopyOnWriteArrayList<>();

    private final Random random = new Random();

    private final AtomicBoolean sendingAcksEnabled = new AtomicBoolean(true);

    /**
     * Creates a new simulated block node server on the specified port.
     *
     * @param port the port to listen on
     */
    public SimulatedBlockNodeServer(final int port) {
        this.port = port;
        this.serviceImpl = new MockBlockStreamServiceImpl();
        this.server = ServerBuilder.forPort(port).addService(serviceImpl).build();
    }

    /**
     * Starts the server.
     *
     * @throws IOException if the server cannot be started
     */
    public void start() throws IOException {
        server.start();
        log.info("Simulated block node server started on port {}", port);
    }

    /**
     * Stops the server with a grace period for shutdown.
     */
    public void stop() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                log.info("Simulated block node server on port {} stopped", port);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Error stopping simulated block node server on port {}", port, e);
            }
        }
    }

    /**
     * Gets the port this server is listening on.
     *
     * @return the port
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
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     */
    public void setEndOfStreamResponse(final EndOfStream.Code responseCode, final long blockNumber) {
        endOfStreamConfig.set(new EndOfStreamConfig(responseCode, blockNumber));
        log.info("Set EndOfStream response to {} for block {} on port {}", responseCode, blockNumber, port);
    }

    /**
     * Send an EndOfStream response immediately to all active streams.
     * This will end all active streams with the specified response code.
     *
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     * @return the last verified block number
     */
    public long sendEndOfStreamImmediately(final EndOfStream.Code responseCode, final long blockNumber) {
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
     * @return the last verified block number
     */
    public long getLastVerifiedBlockNumber() {
        return lastVerifiedBlockNumber.get();
    }

    /**
     * Checks if a specific block number has been fully received (header and proof) by this server.
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
     *
     * @return a set of all received block numbers
     */
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
     * Reset all configured responses to default behavior.
     */
    public void resetResponses() {
        endOfStreamConfig.set(null);
        log.info("Reset all responses to default behavior on port {}", port);
    }

    /**
     * Configuration for EndOfStream responses.
     */
    private record EndOfStreamConfig(EndOfStream.Code responseCode, long blockNumber) {}

    /**
     * Implementation of the BlockStreamService that can be configured to respond
     * with different response codes.
     */
    private class MockBlockStreamServiceImpl extends BlockStreamPublishServiceGrpc.BlockStreamPublishServiceImplBase {
        @Override
        public StreamObserver<org.hiero.block.api.protoc.PublishStreamRequest> publishBlockStream(
                final StreamObserver<org.hiero.block.api.protoc.PublishStreamResponse> responseObserver) {
            // Add the new stream observer to the list of active streams
            activeStreams.add(responseObserver);
            log.info(
                    "New block stream connection established on port {}. Total streams: {}",
                    port,
                    activeStreams.size());

            return new StreamObserver<>() {
                private Long currentBlockNumber = null; // Track block number for this specific stream

                @Override
                public void onNext(final PublishStreamRequest request) {
                    // Acquire lock once for the entire request processing
                    blockTrackingLock.writeLock().lock();
                    try {
                        // Move endOfStreamConfig check inside the lock for thread safety
                        final EndOfStreamConfig config = endOfStreamConfig.getAndSet(null);
                        if (config != null) {
                            sendEndOfStream(responseObserver, config.responseCode(), config.blockNumber());
                            return;
                        }
                        // Iterate through each BlockItem in the request
                        for (final BlockItem item : request.getBlockItems().getBlockItemsList()) {
                            if (item.hasBlockHeader()) {
                                final var header = item.getBlockHeader();
                                final long blockNumber = header.getNumber();
                                // Set the current block number being processed by THIS stream instance
                                currentBlockNumber = blockNumber;
                                log.info(
                                        "Received BlockHeader for block {} on port {} from stream {}",
                                        blockNumber,
                                        port,
                                        responseObserver.hashCode());

                                // Requirement 3: Check if block already exists (header AND proof received)
                                if (blocksWithProofs.contains(blockNumber)) {
                                    log.warn(
                                            "Block {} already fully received (header+proof). Sending BlockAcknowledgement(exists=true) to stream {} on port {}.",
                                            blockNumber,
                                            responseObserver.hashCode(),
                                            port);
                                    buildAndSendBlockAcknowledgement(blockNumber, responseObserver, true);
                                    // Continue to the next BlockItem in the request
                                    continue;
                                }

                                // Requirement 1: Check if another stream is currently sending this block's parts
                                if (streamingBlocks.containsKey(blockNumber)) {
                                    // If it's a different stream trying to send the same header
                                    if (streamingBlocks.get(blockNumber) != responseObserver) {
                                        log.warn(
                                                "Block {} header received from stream {}, but another stream ({}) is already sending parts. Sending SkipBlock to stream {} on port {}.",
                                                blockNumber,
                                                responseObserver.hashCode(),
                                                streamingBlocks.get(blockNumber).hashCode(),
                                                responseObserver.hashCode(),
                                                port);
                                        sendSkipBlock(responseObserver, blockNumber);
                                        // Continue to the next BlockItem in the request
                                        continue;
                                    }
                                    // If it's the same stream sending the header again (e.g., duplicate header item in
                                    // the same request)
                                    log.warn(
                                            "Block {} header received again from the same stream {} while streaming. Ignoring duplicate header item.",
                                            blockNumber,
                                            responseObserver.hashCode());
                                    // Continue to the next BlockItem in the request
                                    continue;
                                }

                                // If block doesn't exist and no one else is streaming it, mark it as header-received
                                // and associate this stream with it.
                                blocksWithHeadersOnly.add(blockNumber);
                                streamingBlocks.put(blockNumber, responseObserver);
                                log.info(
                                        "Accepted BlockHeader for block {}. Stream {} is now sending parts on port {}.",
                                        blockNumber,
                                        responseObserver.hashCode(),
                                        port);

                            } else if (item.hasBlockProof()) {
                                final var proof = item.getBlockProof();
                                final long blockNumber = proof.getBlock();
                                log.info(
                                        "Received BlockProof for block {} on port {} from stream {}",
                                        blockNumber,
                                        port,
                                        responseObserver.hashCode());

                                // Validate proof context
                                if (currentBlockNumber == null
                                        || currentBlockNumber != blockNumber
                                        || !streamingBlocks.containsKey(blockNumber)
                                        || streamingBlocks.get(blockNumber) != responseObserver) {
                                    log.error(
                                            "Received BlockProof for block {} from stream {} on port {}, but stream state is inconsistent (currentBlockNumber={}, expectedStream={}). Ignoring proof.",
                                            blockNumber,
                                            responseObserver.hashCode(),
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
                                        responseObserver.hashCode(),
                                        newLastVerified);

                                // Requirement 2: Send BlockAcknowledgement to ALL connected observers
                                log.info(
                                        "Broadcasting BlockAcknowledgement for block {} (exists=false) to {} active streams on port {}",
                                        blockNumber,
                                        activeStreams.size(),
                                        port);
                                for (final StreamObserver<PublishStreamResponse> observer : activeStreams) {
                                    // Send Ack with blockAlreadyExists=false
                                    buildAndSendBlockAcknowledgement(blockNumber, observer, false);
                                }

                                // Reset currentBlockNumber for this stream, as it finished sending this block
                                currentBlockNumber = null;
                            }
                        } // End of loop through BlockItems
                    } finally {
                        blockTrackingLock.writeLock().unlock();
                    }
                }

                @Override
                public void onError(final Throwable t) {
                    log.error("Error in block stream on port {}: {}", port, t.getMessage(), t);
                    handleStreamError(responseObserver);
                }

                @Override
                public void onCompleted() {
                    log.info("Block stream completed on port {} for stream {}", port, responseObserver.hashCode());
                    // Just remove the stream normally on completion, no resend needed.
                    removeStreamFromTracking(responseObserver);
                    try {
                        responseObserver.onCompleted();
                    } catch (final Exception e) {
                        log.warn(
                                "Exception calling onCompleted for stream {} on port {}: {}",
                                responseObserver.hashCode(),
                                port,
                                e.getMessage());
                    }
                }
            };
        }

        /**
         * Sends an EndOfStream response to all active streams.
         *
         * @param responseCode the response code to send
         * @param blockNumber the block number to include
         */
        public void sendEndOfStreamToAllStreams(final EndOfStream.Code responseCode, final long blockNumber) {
            log.info(
                    "Sending EndOfStream ({}, block {}) to {} active streams on port {}",
                    responseCode,
                    blockNumber,
                    activeStreams.size(),
                    port);
            blockTrackingLock.writeLock().lock(); // Lock needed to safely iterate and modify activeStreams potentially
            try {
                final List<StreamObserver<PublishStreamResponse>> streamsToRemove = new ArrayList<>();
                for (final StreamObserver<PublishStreamResponse> observer : activeStreams) {
                    try {
                        sendEndOfStream(observer, responseCode, blockNumber);
                        // Assuming EndOfStream terminates the connection from server side perspective
                        observer.onCompleted();
                        streamsToRemove.add(observer); // Mark for removal after iteration
                    } catch (final Exception e) {
                        log.error("Failed to send EndOfStream to stream {} on port {}", observer.hashCode(), port, e);
                        streamsToRemove.add(observer); // Remove problematic stream
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
         *
         * @param blockNumber the block number to skip
         */
        public void sendSkipBlockToAllStreams(final long blockNumber) {
            log.info(
                    "Sending SkipBlock for block {} to {} active streams on port {}",
                    blockNumber,
                    activeStreams.size(),
                    port);
            // No lock needed for read-only iteration on CopyOnWriteArrayList
            for (final StreamObserver<PublishStreamResponse> observer : activeStreams) {
                try {
                    sendSkipBlock(observer, blockNumber);
                } catch (final Exception e) {
                    log.error("Failed to send SkipBlock to stream {} on port {}", observer.hashCode(), port, e);
                    // Decide if we should remove the stream on failure
                    // removeStreamFromTracking(observer);
                }
            }
        }

        /**
         * Sends a ResendBlock response to all active streams.
         *
         * @param blockNumber the block number to resend
         */
        public void sendResendBlockToAllStreams(final long blockNumber) {
            log.info(
                    "Sending ResendBlock for block {} to {} active streams on port {}",
                    blockNumber,
                    activeStreams.size(),
                    port);
            // No lock needed for read-only iteration on CopyOnWriteArrayList
            for (final StreamObserver<PublishStreamResponse> observer : activeStreams) {
                try {
                    sendResendBlock(observer, blockNumber);
                } catch (final Exception e) {
                    log.error("Failed to send ResendBlock to stream {} on port {}", observer.hashCode(), port, e);
                    // Decide if we should remove the stream on failure
                    // removeStreamFromTracking(observer);
                }
            }
        }

        // Helper methods for sending specific responses

        private void sendEndOfStream(
                final StreamObserver<PublishStreamResponse> observer,
                final EndOfStream.Code responseCode,
                final long blockNumber) {
            final EndOfStream endOfStream = EndOfStream.newBuilder()
                    .setStatus(responseCode)
                    .setBlockNumber(blockNumber)
                    .build();
            final PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().setEndStream(endOfStream).build();
            observer.onNext(response);
            log.debug(
                    "Sent EndOfStream ({}, block {}) to stream {} on port {}",
                    responseCode,
                    blockNumber, // blockNumber from config is potentially confusing here, using lastVerified is safer
                    observer.hashCode(),
                    port);
        }

        private void sendSkipBlock(final StreamObserver<PublishStreamResponse> observer, final long blockNumber) {
            final PublishStreamResponse.SkipBlock skipBlock = PublishStreamResponse.SkipBlock.newBuilder()
                    .setBlockNumber(blockNumber)
                    .build();
            final PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().setSkipBlock(skipBlock).build();
            observer.onNext(response);
            log.debug("Sent SkipBlock for block {} to stream {} on port {}", blockNumber, observer.hashCode(), port);
        }

        private void sendResendBlock(final StreamObserver<PublishStreamResponse> observer, final long blockNumber) {
            final ResendBlock resendBlock =
                    ResendBlock.newBuilder().setBlockNumber(blockNumber).build();
            final PublishStreamResponse response = PublishStreamResponse.newBuilder()
                    .setResendBlock(resendBlock)
                    .build();
            observer.onNext(response);
            log.debug("Sent ResendBlock for block {} to stream {} on port {}", blockNumber, observer.hashCode(), port);
        }

        /**
         * Removes a stream observer from active tracking and cleans up any associated state.
         * Acquires the necessary lock.
         *
         * @param observer The observer to remove.
         */
        private void removeStreamFromTracking(final StreamObserver<PublishStreamResponse> observer) {
            blockTrackingLock.writeLock().lock();
            try {
                removeStreamFromTrackingInternal(observer);
            } finally {
                blockTrackingLock.writeLock().unlock();
            }
        }

        /**
         * Internal helper to remove stream observer state. MUST be called while holding the write lock.
         *
         * @param observer The observer to remove.
         */
        private void removeStreamFromTrackingInternal(final StreamObserver<PublishStreamResponse> observer) {
            if (activeStreams.remove(observer)) {
                log.info(
                        "Removed stream observer {} from active list on port {}. Remaining: {}",
                        observer.hashCode(),
                        port,
                        activeStreams.size());
            }
            // Check if this stream was actively sending a block and remove it from tracking
            streamingBlocks.entrySet().removeIf(entry -> {
                if (entry.getValue() == observer) {
                    final long blockNumber = entry.getKey();
                    log.warn(
                            "Stream {} disconnected while sending block {}. Removing from streaming state on port {}.",
                            observer.hashCode(),
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
         *
         * @param erroredObserver The observer that encountered the error.
         */
        private void handleStreamError(final StreamObserver<PublishStreamResponse> erroredObserver) {
            Long blockNumberOnError = null;
            // Find if this observer was streaming a block
            blockTrackingLock.readLock().lock(); // Read lock sufficient to check streamingBlocks
            try {
                final Optional<Map.Entry<Long, StreamObserver<PublishStreamResponse>>> entry =
                        streamingBlocks.entrySet().stream()
                                .filter(e -> e.getValue() == erroredObserver)
                                .findFirst();
                if (entry.isPresent()) {
                    blockNumberOnError = entry.get().getKey();
                    log.warn(
                            "Stream {} encountered an error while streaming block {} on port {}. Attempting to request resend.",
                            erroredObserver.hashCode(),
                            blockNumberOnError,
                            port);
                }
            } finally {
                blockTrackingLock.readLock().unlock();
            }

            // Perform cleanup *after* checking state and potentially initiating resend
            removeStreamFromTracking(erroredObserver);

            // If an error occurred *while* this stream was sending block parts
            if (blockNumberOnError != null) {
                // Find other active streams
                final List<StreamObserver<PublishStreamResponse>> otherStreams =
                        activeStreams.stream().filter(s -> s != erroredObserver).toList();

                if (!otherStreams.isEmpty()) {
                    // Select a random stream from the others
                    final StreamObserver<PublishStreamResponse> chosenStream =
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
                            erroredObserver.hashCode(),
                            port);
                }
            }
        }
    }

    /**
     * Builds and sends a BlockAcknowledgement response to a specific observer.
     *
     * @param blockNumber The block number being acknowledged.
     * @param responseObserver The observer to send the acknowledgment to.
     * @param blockAlreadyExists Indicates if the block was already fully processed.
     */
    private void buildAndSendBlockAcknowledgement(
            final long blockNumber,
            final StreamObserver<PublishStreamResponse> responseObserver,
            final boolean blockAlreadyExists) {

        if (!sendingAcksEnabled.get()) {
            return;
        }

        final PublishStreamResponse.BlockAcknowledgement ack = PublishStreamResponse.BlockAcknowledgement.newBuilder()
                .setBlockNumber(blockNumber)
                .build();
        final PublishStreamResponse response =
                PublishStreamResponse.newBuilder().setAcknowledgement(ack).build();
        try {
            responseObserver.onNext(response);
            log.debug(
                    "Sent BlockAcknowledgement for block {} (exists={}) to stream {} on port {}. Last verified: {}",
                    blockNumber,
                    blockAlreadyExists,
                    responseObserver.hashCode(),
                    port,
                    lastVerifiedBlockNumber.get());
        } catch (Exception e) {
            log.error(
                    "Failed to send BlockAcknowledgement for block {} to stream {} on port {}. Removing stream.",
                    blockNumber,
                    responseObserver.hashCode(),
                    port,
                    e);
            // If we can't send an ack, the stream is likely broken. Remove it.
            serviceImpl.removeStreamFromTracking(responseObserver);
        }
    }
}
