// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.protoc.PublishStreamResponse.EndOfStream;
import com.hedera.hapi.block.protoc.PublishStreamResponseCode;
import com.hedera.hapi.block.stream.protoc.BlockItem;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A simulated block node server that implements the block streaming gRPC service.
 * This server can be configured to respond with different response codes and simulate
 * various error conditions for testing purposes.
 */
public class SimulatedBlockNodeServer {
    private static final Logger log = LogManager.getLogger(SimulatedBlockNodeServer.class);

    private final Server server;
    private final int port;
    private final BlockStreamServiceImpl serviceImpl;

    // Configuration for EndOfStream responses
    private final AtomicReference<EndOfStreamConfig> endOfStreamConfig = new AtomicReference<>();

    // Track the last verified block number
    private final AtomicReference<Long> lastVerifiedBlockNumber = new AtomicReference<>(0L);

    // Track all received block numbers
    private final Set<Long> receivedBlockNumbers = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new simulated block node server on the specified port.
     *
     * @param port the port to listen on
     */
    public SimulatedBlockNodeServer(int port) {
        this.port = port;
        this.serviceImpl = new BlockStreamServiceImpl();
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
    public void setEndOfStreamResponse(PublishStreamResponseCode responseCode, long blockNumber) {
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
    public long sendEndOfStreamImmediately(PublishStreamResponseCode responseCode, long blockNumber) {
        serviceImpl.sendEndOfStreamToAllStreams(responseCode, blockNumber);
        log.info(
                "Sent immediate EndOfStream response with code {} for block {} on port {}",
                responseCode,
                blockNumber,
                port);
        return lastVerifiedBlockNumber.get();
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
        return receivedBlockNumbers.contains(blockNumber);
    }

    /**
     * Gets all block numbers that have been received by this server.
     *
     * @return a set of all received block numbers
     */
    public Set<Long> getReceivedBlockNumbers() {
        return Set.copyOf(receivedBlockNumbers);
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
        private final PublishStreamResponseCode responseCode;
        private final long blockNumber;

        public EndOfStreamConfig(PublishStreamResponseCode responseCode, long blockNumber) {
            this.responseCode = responseCode;
            this.blockNumber = blockNumber;
        }

        public PublishStreamResponseCode getResponseCode() {
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
    private class BlockStreamServiceImpl extends BlockStreamServiceGrpc.BlockStreamServiceImplBase {
        // Keep track of all active stream observers so we can send immediate responses
        private final List<StreamObserver<PublishStreamResponse>> activeStreams = new CopyOnWriteArrayList<>();

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

                    // Default behavior: acknowledge block proofs
                    if (request.getBlockItems().getBlockItemsList().stream().anyMatch(BlockItem::hasBlockProof)) {
                        List<BlockItem> blockProofs = request.getBlockItems().getBlockItemsList().stream()
                                .filter(BlockItem::hasBlockProof)
                                .toList();
                        if (blockProofs.size() > 1) {
                            log.error("Received more than one block proof in a single request. This is not expected.");
                        }
                        BlockItem blockProof = blockProofs.getFirst();
                        long blockNumber = blockProof.getBlockProof().getBlock();
                        log.info(
                                "Received block proof for block {} with signature {}",
                                blockNumber,
                                blockProof.getBlockProof().getBlockSignature());

                        // Update the last verified block number
                        lastVerifiedBlockNumber.set(blockNumber);

                        // Add to the set of received block numbers
                        receivedBlockNumbers.add(blockNumber);

                        com.hedera.hapi.block.protoc.PublishStreamResponse.BlockAcknowledgement.Builder
                                blockAcknowledgement =
                                        com.hedera.hapi.block.protoc.PublishStreamResponse.BlockAcknowledgement
                                                .newBuilder()
                                                .setBlockNumber(blockNumber)
                                                .setBlockAlreadyExists(false);

                        // If this request contains a block proof, send an acknowledgement
                        responseObserver.onNext(PublishStreamResponse.newBuilder()
                                .setAcknowledgement(Acknowledgement.newBuilder()
                                        .setBlockAck(blockAcknowledgement)
                                        .build())
                                .build());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    log.error("Error in block stream on port {}", port, t);
                    activeStreams.remove(responseObserver);
                }

                @Override
                public void onCompleted() {
                    log.info("Block stream completed on port {}", port);
                    responseObserver.onCompleted();
                    activeStreams.remove(responseObserver);
                }
            };
        }

        /**
         * Send an EndOfStream response to all active streams.
         *
         * @param responseCode the response code to send
         * @param blockNumber the block number to include in the response
         */
        public void sendEndOfStreamToAllStreams(PublishStreamResponseCode responseCode, long blockNumber) {
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

        private void sendEndOfStream(
                StreamObserver<PublishStreamResponse> observer,
                PublishStreamResponseCode responseCode,
                long blockNumber) {
            try {
                observer.onNext(PublishStreamResponse.newBuilder()
                        .setStatus(EndOfStream.newBuilder()
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
    }
}
