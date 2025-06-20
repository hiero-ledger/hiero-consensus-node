// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.protoc.BlockItem;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    // Track the last verified block number
    private final AtomicReference<Long> lastVerifiedBlockNumber = new AtomicReference<>(0L);

    // Locks for synchronizing access to block tracking data structures
    private final ReadWriteLock blockTrackingLock = new ReentrantReadWriteLock();

    // Track all blocks for which we have received proofs
    private final Set<Long> blocksWithProofs = ConcurrentHashMap.newKeySet();

    // Track all blocks for which we have received headers
    private final Set<Long> blocksWithHeaders = ConcurrentHashMap.newKeySet();

    // Track which streams have already received acknowledgments for specific blocks
    private final Map<Long, Set<StreamObserver<PublishStreamResponse>>> acknowledgedStreams = new ConcurrentHashMap<>();

    /**
     * Creates a new simulated block node server on the specified port.
     *
     * @param port the port to listen on
     */
    public SimulatedBlockNodeServer(int port) {
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
            } catch (InterruptedException e) {
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

    /**
     * Configure the server to respond with a specific EndOfStream response code
     * on the next block item.
     *
     * @param responseCode the response code to send
     * @param blockNumber the block number to include in the response
     */
    public void setEndOfStreamResponse(EndOfStream.Code responseCode, long blockNumber) {
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
    public long sendEndOfStreamImmediately(EndOfStream.Code responseCode, long blockNumber) {
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
    public void sendSkipBlockImmediately(long blockNumber) {
        serviceImpl.sendSkipBlockToAllStreams(blockNumber);
        log.info("Sent immediate SkipBlock response for block {} on port {}", blockNumber, port);
    }

    /**
     * Send a ResendBlock response immediately to all active streams.
     * This will instruct all active streams to resend the specified block.
     *
     * @param blockNumber the block number to resend
     */
    public void sendResendBlockImmediately(long blockNumber) {
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
     * Checks if a specific block number has been received by this server.
     *
     * @param blockNumber the block number to check
     * @return true if the block has been received, false otherwise
     */
    public boolean hasReceivedBlock(long blockNumber) {
        blockTrackingLock.readLock().lock();
        try {
            return blocksWithProofs.contains(blockNumber);
        } finally {
            blockTrackingLock.readLock().unlock();
        }
    }

    /**
     * Gets all block numbers that have been received by this server.
     *
     * @return a set of all received block numbers
     */
    public Set<Long> getReceivedBlockNumbers() {
        blockTrackingLock.readLock().lock();
        try {
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
    private static class EndOfStreamConfig {
        private final EndOfStream.Code responseCode;
        private final long blockNumber;

        public EndOfStreamConfig(EndOfStream.Code responseCode, long blockNumber) {
            this.responseCode = responseCode;
            this.blockNumber = blockNumber;
        }

        public EndOfStream.Code getResponseCode() {
            return responseCode;
        }

        public long getBlockNumber() {
            return blockNumber;
        }
    }

    /**
     * Implementation of the BlockStreamService that can be configured to respond
     * with different response codes.
     */
    private class MockBlockStreamServiceImpl extends BlockStreamPublishServiceGrpc.BlockStreamPublishServiceImplBase {
        // Keep track of all active stream observers so we can send immediate responses
        private final List<StreamObserver<PublishStreamResponse>> activeStreams = new CopyOnWriteArrayList<>();

        // Track streams that have been sent SkipBlock for specific block numbers
        private final Map<Long, Set<StreamObserver<PublishStreamResponse>>> skipBlockSentStreams =
                new ConcurrentHashMap<>();

        @Override
        public StreamObserver<PublishStreamRequest> publishBlockStream(
                StreamObserver<PublishStreamResponse> responseObserver) {
            // Add the stream to active streams as soon as the connection is established
            activeStreams.add(responseObserver);
            log.info(
                    "New block stream connection established on port {}. Active streams: {}",
                    port,
                    activeStreams.size());

            return new StreamObserver<>() {
                @Override
                public void onNext(PublishStreamRequest request) {
                    // Check if we should send an EndOfStream response
                    EndOfStreamConfig config = endOfStreamConfig.get();
                    if (config != null) {
                        sendEndOfStream(responseObserver, config.getResponseCode(), config.getBlockNumber());
                        return;
                    }

                    // Process block headers
                    List<BlockItem> blockHeaders = request.getBlockItems().getBlockItemsList().stream()
                            .filter(BlockItem::hasBlockHeader)
                            .toList();

                    if (blockHeaders.size() > 1) {
                        log.error("Received multiple block headers in a single request on port {}", port);
                        throw new IllegalStateException("Received multiple block headers in a single request");
                    }

                    for (BlockItem blockHeader : blockHeaders) {
                        long blockNumber = blockHeader.getBlockHeader().getNumber();
                        log.info("Received block header for block {}", blockNumber);

                        blockTrackingLock.writeLock().lock();
                        try {
                            boolean alreadyHasHeader = blocksWithHeaders.contains(blockNumber);
                            // Always add to the set of blocks with headers
                            blocksWithHeaders.add(blockNumber);

                            // If we've already seen this block header, send SkipBlock
                            if (alreadyHasHeader) {
                                log.info("Already received header for block {}, sending SkipBlock", blockNumber);
                                sendSkipBlock(responseObserver, blockNumber);
                                // Note: The sendSkipBlock method already tracks that we've sent a SkipBlock

                                // If we've already received the block proof, immediately follow with
                                // BlockAcknowledgement
                                if (blocksWithProofs.contains(blockNumber)) {
                                    log.info(
                                            "Already have proof for block {}, sending BlockAcknowledgement immediately after SkipBlock",
                                            blockNumber);
                                    sendAcknowledgmentIfNeeded(blockNumber, responseObserver);
                                } else {
                                    log.info(
                                            "No proof yet for block {}, acknowledgment will be sent when proof is received",
                                            blockNumber);
                                }
                            }
                        } finally {
                            blockTrackingLock.writeLock().unlock();
                        }
                    }

                    // Process block proofs
                    List<BlockItem> blockProofs = request.getBlockItems().getBlockItemsList().stream()
                            .filter(BlockItem::hasBlockProof)
                            .toList();

                    for (BlockItem blockProof : blockProofs) {
                        long blockNumber = blockProof.getBlockProof().getBlock();
                        log.info(
                                "Received block proof for block {} with signature {}",
                                blockNumber,
                                blockProof.getBlockProof().getBlockSignature());

                        // Update the last verified block number
                        lastVerifiedBlockNumber.set(blockNumber);

                        blockTrackingLock.writeLock().lock();
                        try {
                            // Add to the set of blocks with proofs
                            blocksWithProofs.add(blockNumber);

                            // 1. Send acknowledgement to the current observer (the one that sent the proof)
                            log.info("Sending BlockAcknowledgement to current observer for block {}", blockNumber);
                            sendAcknowledgmentIfNeeded(blockNumber, responseObserver);

                            // 2. Get the set of streams that have been sent a SkipBlock for this block number
                            //    These are the streams that are pending to receive a block acknowledgment
                            Set<StreamObserver<PublishStreamResponse>> pendingStreams =
                                    skipBlockSentStreams.getOrDefault(blockNumber, ConcurrentHashMap.newKeySet());

                            // Only send acknowledgements to streams that have received a SkipBlock for this block
                            // and are still active
                            if (!pendingStreams.isEmpty()) {
                                log.info(
                                        "Sending BlockAcknowledgement to {} pending streams for block {}",
                                        pendingStreams.size(),
                                        blockNumber);

                                for (StreamObserver<PublishStreamResponse> pendingObserver : pendingStreams) {
                                    // Skip the current observer as it was already acknowledged
                                    if (pendingObserver != responseObserver
                                            && activeStreams.contains(pendingObserver)) {
                                        sendAcknowledgmentIfNeeded(blockNumber, pendingObserver);
                                    }
                                }
                            }
                        } finally {
                            blockTrackingLock.writeLock().unlock();
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Error in block stream on port {}", port, t);
                    activeStreams.remove(responseObserver);

                    // Remove this stream from all tracking maps
                    removeStreamFromTracking(responseObserver);
                }

                @Override
                public void onCompleted() {
                    log.info("Block stream completed on port {}", port);
                    responseObserver.onCompleted();
                    activeStreams.remove(responseObserver);

                    // Remove this stream from all tracking maps
                    removeStreamFromTracking(responseObserver);
                }
            };
        }

        /**
         * Send an EndOfStream response to all active streams.
         *
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         */
        public void sendEndOfStreamToAllStreams(EndOfStream.Code responseCode, long blockNumber) {
            List<StreamObserver<PublishStreamResponse>> streams = new ArrayList<>(activeStreams);
            log.info(
                    "Sending EndOfStream with code {} for block {} to {} active streams on port {}",
                    responseCode,
                    blockNumber,
                    streams.size(),
                    port);

            for (StreamObserver<PublishStreamResponse> observer : streams) {
                sendEndOfStream(observer, responseCode, blockNumber);
            }
        }

        /**
         * Send a SkipBlock response to all active streams.
         *
         * @param blockNumber the block number to skip
         */
        public void sendSkipBlockToAllStreams(long blockNumber) {
            List<StreamObserver<PublishStreamResponse>> streams = new ArrayList<>(activeStreams);
            log.info(
                    "Sending SkipBlock for block {} to {} active streams on port {}",
                    blockNumber,
                    streams.size(),
                    port);

            for (StreamObserver<PublishStreamResponse> observer : streams) {
                sendSkipBlock(observer, blockNumber);
                // Note: The sendSkipBlock method already tracks that we've sent a SkipBlock
            }
        }

        /**
         * Send a ResendBlock response to all active streams.
         *
         * @param blockNumber the block number to resend
         */
        public void sendResendBlockToAllStreams(long blockNumber) {
            List<StreamObserver<PublishStreamResponse>> streams = new ArrayList<>(activeStreams);
            log.info(
                    "Sending ResendBlock for block {} to {} active streams on port {}",
                    blockNumber,
                    streams.size(),
                    port);

            for (StreamObserver<PublishStreamResponse> observer : streams) {
                sendResendBlock(observer, blockNumber);
            }
        }

        private void sendEndOfStream(
                StreamObserver<PublishStreamResponse> observer, EndOfStream.Code responseCode, long blockNumber) {
            try {
                observer.onNext(PublishStreamResponse.newBuilder()
                        .setEndStream(EndOfStream.newBuilder()
                                .setStatus(responseCode)
                                .setBlockNumber(blockNumber)
                                .build())
                        .build());
                observer.onCompleted();
                activeStreams.remove(observer);
            } catch (Exception e) {
                log.error("Error sending EndOfStream response on port {}", port, e);
            }
        }

        private void sendSkipBlock(StreamObserver<PublishStreamResponse> observer, long blockNumber) {

            PublishStreamResponse.SkipBlock skipBlock = PublishStreamResponse.SkipBlock.newBuilder()
                    .setBlockNumber(blockNumber)
                    .build();

            PublishStreamResponse response =
                    PublishStreamResponse.newBuilder().setSkipBlock(skipBlock).build();

            observer.onNext(response);

            // Track that we've sent a SkipBlock for this block number to this stream
            skipBlockSentStreams
                    .computeIfAbsent(blockNumber, k -> ConcurrentHashMap.newKeySet())
                    .add(observer);
        }

        private void sendResendBlock(StreamObserver<PublishStreamResponse> observer, long blockNumber) {
            try {
                observer.onNext(PublishStreamResponse.newBuilder()
                        .setResendBlock(ResendBlock.newBuilder()
                                .setBlockNumber(blockNumber)
                                .build())
                        .build());
            } catch (Exception e) {
                log.error("Error sending ResendBlock response on port {}", port, e);
            }
        }

        /**
         * Removes a stream from all tracking maps.
         *
         * @param observer the stream observer to remove
         */
        private void removeStreamFromTracking(StreamObserver<PublishStreamResponse> observer) {
            // Remove from skipBlockSentStreams
            for (Set<StreamObserver<PublishStreamResponse>> streams : skipBlockSentStreams.values()) {
                streams.remove(observer);
            }

            // Remove from acknowledgedStreams
            for (Set<StreamObserver<PublishStreamResponse>> streams : acknowledgedStreams.values()) {
                streams.remove(observer);
            }
        }
    }

    /**
     * Helper method to send an acknowledgment only if it hasn't been sent to this stream for this block already.
     * This prevents duplicate acknowledgments.
     *
     * @param blockNumber the block number to acknowledge
     * @param observer the stream observer to send the acknowledgment to
     */
    private void sendAcknowledgmentIfNeeded(long blockNumber, StreamObserver<PublishStreamResponse> observer) {
        // Get or create the set of observers that have been acknowledged for this block
        Set<StreamObserver<PublishStreamResponse>> acked =
                acknowledgedStreams.computeIfAbsent(blockNumber, k -> ConcurrentHashMap.newKeySet());

        // Only send acknowledgment if this observer hasn't been acknowledged for this block yet
        if (acked.add(observer)) {
            try {
                buildAndSendBlockAcknowledgement(blockNumber, observer);
                log.info("Sent acknowledgment for block {} to stream", blockNumber);
            } catch (Exception e) {
                log.error("Error sending BlockAcknowledgement for block {}", blockNumber, e);
            }
        } else {
            log.debug("Skipping duplicate acknowledgment for block {} to stream", blockNumber);
        }
    }

    /**
     * Builds and sends a block acknowledgment message to the specified stream.
     *
     * @param blockNumber the block number to acknowledge
     * @param responseObserver the stream observer to send the acknowledgment to
     */
    private static void buildAndSendBlockAcknowledgement(
            long blockNumber, StreamObserver<PublishStreamResponse> responseObserver) {
        try {
            // Create a dummy hash using a hex string (which is guaranteed to be valid)
            // SHA-384 hash is 48 bytes (96 hex characters)
            String hexHash =
                    "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
            ByteString dummyHash = ByteString.fromHex(hexHash);

            // Build the BlockAcknowledgement message
            PublishStreamResponse.BlockAcknowledgement blockAck =
                    PublishStreamResponse.BlockAcknowledgement.newBuilder()
                            .setBlockNumber(blockNumber)
                            .setBlockRootHash(dummyHash)
                            .setBlockAlreadyExists(false)
                            .build();

            // Build the full response
            PublishStreamResponse response = PublishStreamResponse.newBuilder()
                    .setAcknowledgement(blockAck)
                    .build();

            // Send the response
            responseObserver.onNext(response);
        } catch (Exception e) {
            log.error("Error building or sending BlockAcknowledgement for block {}", blockNumber, e);
        }
    }
}
