// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_384;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.block.protoc.BlockStreamServiceGrpc;
import com.hedera.hapi.block.protoc.PublishStreamRequest;
import com.hedera.hapi.block.protoc.PublishStreamResponse;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class BlockNodeConnectionManagerTest {
    @LoggingSubject
    BlockNodeConnectionManager blockNodeConnectionManager;

    @LoggingTarget
    private LogCaptor logCaptor;

    @Mock
    ConfigProvider mockConfigProvider;

    private static Server testServer;

    @BeforeAll
    static void beforeAll() throws IOException {
        final var configExtractor = new BlockNodeConfigExtractor("./src/test/resources/bootstrap");
        final int testServerPort = configExtractor.getAllNodes().getFirst().port();
        testServer = ServerBuilder.forPort(testServerPort)
                .addService(new BlockStreamServiceTestImpl())
                .build();
        testServer.start();
    }

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.writerMode", "FILE_AND_GRPC")
                .withValue("blockStream.blockNodeConnectionFileDir", "./src/test/resources/bootstrap")
                .getOrCreateConfig();
        given(mockConfigProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1));
        blockNodeConnectionManager = new BlockNodeConnectionManager(mockConfigProvider);
        blockNodeConnectionManager.waitForConnection(Duration.ofSeconds(1L));
    }

    @Test
    void testNewBlockNodeConnectionManager() {
        final var expectedGrpcEndpoint =
                BlockStreamServiceGrpc.getPublishBlockStreamMethod().getBareMethodName();
        assertEquals(expectedGrpcEndpoint, blockNodeConnectionManager.getGrpcEndPoint());
    }

    @Test
    void testStreamBlockHeaderToConnections() {
        final long blockNumber = 1L;
        final var blockHeader = BlockHeader.newBuilder()
                .number(blockNumber)
                .hashAlgorithm(SHA2_384)
                .build();

        blockNodeConnectionManager.streamBlockHeaderToConnections(
                blockNumber, BlockHeader.PROTOBUF.toBytes(blockHeader));

        assertThat(logCaptor.infoLogs())
                .contains(
                        "Streaming block header for block 1 to 1 active connections",
                        "Successfully streamed block header for block 1 to localhost:8080");
    }

    @Test
    void testStreamBlockToConnections() {
        final long blockNumber = 1L;
        final var blockHeader = BlockHeader.newBuilder()
                .number(blockNumber)
                .hashAlgorithm(SHA2_384)
                .build();
        final var blockProof =
                BlockItem.newBuilder().blockProof(BlockProof.DEFAULT).build();
        final var block = new BlockState(
                blockNumber,
                List.of(BlockHeader.PROTOBUF.toBytes(blockHeader), BlockItem.PROTOBUF.toBytes(blockProof)));

        blockNodeConnectionManager.streamBlockToConnections(block);

        assertThat(logCaptor.infoLogs())
                .contains(
                        "Streaming block items for block 1 to 1 active connections",
                        "Successfully streamed block items for block 1 to localhost:8080");
    }

    @AfterAll
    static void afterAll() {
        testServer.shutdownNow();
    }

    private static class BlockStreamServiceTestImpl extends BlockStreamServiceGrpc.BlockStreamServiceImplBase {
        @Override
        public StreamObserver<PublishStreamRequest> publishBlockStream(
                StreamObserver<PublishStreamResponse> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(PublishStreamRequest request) {
                    // no-op
                }

                @Override
                public void onError(Throwable t) {
                    // no-op
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            };
        }
    }
}
