// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.simulator;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.protoc.PublishStreamResponse.Acknowledgement;
import com.hedera.hapi.block.stream.protoc.BlockItem;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A simulated block node server that implements the block streaming gRPC service.
 * This server simply logs received blocks and acknowledges them.
 */
public class SimulatedBlockNodeServer {
    private static final Logger log = LogManager.getLogger(SimulatedBlockNodeServer.class);

    private final Server server;
    private final int port;

    public SimulatedBlockNodeServer(int port) {
        this.port = port;
        this.server = ServerBuilder.forPort(port)
                .addService(new BlockStreamServiceImpl())
                .build();
    }

    public void start() throws IOException {
        server.start();
        log.info("Simulated block node server started on port {}", port);
    }

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

    public int getPort() {
        return port;
    }

    /**
     * Implementation of the BlockStreamService that logs received blocks and sends acknowledgements.
     */
    private static class BlockStreamServiceImpl extends BlockStreamServiceGrpc.BlockStreamServiceImplBase {
        @Override
        public StreamObserver<PublishStreamRequest> publishBlockStream(
                StreamObserver<PublishStreamResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(PublishStreamRequest request) {
                    log.info(
                            "Received block stream request with {} block items",
                            request.getBlockItems().getBlockItemsCount());

                    if (request.getBlockItems().getBlockItemsList().stream().anyMatch(BlockItem::hasBlockProof)) {
                        List<BlockItem> blockProofs = request.getBlockItems().getBlockItemsList().stream()
                                .filter(BlockItem::hasBlockProof)
                                .toList();
                        if (blockProofs.size() > 1) {
                            log.error("Received more than one block proof in a single request. This is not expected.");
                        }
                        BlockItem blockProof = blockProofs.getFirst();
                        log.info(
                                "Received block proof for block {} with signature {}",
                                blockProof.getBlockProof().getBlock(),
                                blockProof.getBlockProof().getBlockSignature());

                        com.hedera.hapi.block.protoc.PublishStreamResponse.BlockAcknowledgement.Builder
                                blockAcknowledgement =
                                        com.hedera.hapi.block.protoc.PublishStreamResponse.BlockAcknowledgement
                                                .newBuilder()
                                                .setBlockNumber(blockProof
                                                        .getBlockProof()
                                                        .getBlock())
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
                    log.error("Error in block stream", t);
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
