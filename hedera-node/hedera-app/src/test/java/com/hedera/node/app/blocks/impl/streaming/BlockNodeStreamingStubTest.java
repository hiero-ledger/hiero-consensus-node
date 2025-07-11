// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.google.protobuf.ByteString;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import org.hiero.block.api.protoc.BlockItemSet;
import org.hiero.block.api.protoc.BlockStreamPublishServiceGrpc;
import org.hiero.block.api.protoc.PublishStreamRequest;
import org.hiero.block.api.protoc.PublishStreamResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockNodeStreamingStubTest {

    public static final int PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES = 4194304;
    private static Server server;
    private BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub stub;

    private static CountDownLatch latch = new CountDownLatch(5000);

    @BeforeAll
    static void setup() throws IOException {
        // Mock implementation of the BlockStreamPublishService
        BlockStreamPublishServiceGrpc.BlockStreamPublishServiceImplBase serviceImpl =
                new BlockStreamPublishServiceGrpc.BlockStreamPublishServiceImplBase() {

                    @Override
                    public StreamObserver<PublishStreamRequest> publishBlockStream(
                            StreamObserver<PublishStreamResponse> responseObserver) {
                        return new StreamObserver<>() {
                            @Override
                            public void onNext(PublishStreamRequest request) {
                                // No-op for this test, just simulating high-rate data on stream
                                latch.countDown();
                            }

                            @Override
                            public void onError(Throwable t) {
                                responseObserver.onError(t);
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onCompleted();
                            }
                        };
                    }
                };

        // Start the gRPC server
        server = ServerBuilder.forPort(0).addService(serviceImpl).build().start();
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination();
        }
    }

    @Test
    @Disabled
    void testHighRateDataPublishing() {
        BlockStreamPublishServiceGrpc.BlockStreamPublishServiceStub stub =
                BlockStreamPublishServiceGrpc.newStub(ManagedChannelBuilder.forAddress("localhost", server.getPort())
                        .usePlaintext()
                        .build());

        ClientCallStreamObserver<PublishStreamRequest> requestObserver =
                (ClientCallStreamObserver<PublishStreamRequest>) stub.publishBlockStream(new StreamObserver<>() {
                    @Override
                    public void onNext(PublishStreamResponse value) {
                        // No-op for this test, just simulating high-rate data on stream
                    }

                    @Override
                    public void onError(Throwable t) {
                        // No-op for this test, just simulating high-rate data on stream
                    }

                    @Override
                    public void onCompleted() {
                        // No-op for this test, just simulating high-rate data on stream
                    }
                });

        byte[] largeData = new byte[1024 * 1024 * 2]; // 2MB
        ByteString largeDataByteString = ByteString.copyFrom(largeData);

        PublishStreamRequest testRequest = PublishStreamRequest.newBuilder()
                .setBlockItems(BlockItemSet.newBuilder()
                        .addBlockItems(com.hedera.hapi.block.stream.protoc.BlockItem.newBuilder()
                                .setEventTransaction(com.hedera.hapi.platform.event.legacy.EventTransaction.newBuilder()
                                        .setApplicationTransaction(largeDataByteString)
                                        .build())
                                .build())
                        .build())
                .build();

        // Simulate high-rate data publishing
        int cnt = 0;
        while (latch.getCount() > 0) {
            if (requestObserver.isReady() && cnt < 5000) {
                requestObserver.onNext(testRequest);
                cnt++;
            } else {
                // Park the thread for 10ms in nanos
                LockSupport.parkNanos(10_000_000); // 10 milliseconds in nanoseconds
            }
        }
        requestObserver.onCompleted();
    }

    @Test
    void testPublishStreamRequestSizeAtLimit() {
        BlockState blockState = new BlockState(0);

        byte[] largeData = new byte[PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES - 20];
        Bytes largeDataByteString = Bytes.wrap(largeData);

        BlockItem largeItem = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(largeDataByteString)
                        .build())
                .build();

        blockState.addItem(largeItem);
        blockState.processPendingItems(2, PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES);
        org.hiero.block.api.PublishStreamRequest request = blockState.getRequest(0);

        assertThat(request).isNull();
    }

    @Test
    void testPublishStreamRequestSizeAtLimitMinusOneByte() {
        BlockState blockState = new BlockState(0);

        byte[] largeData = new byte[PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES - 21];
        Bytes largeDataByteString = Bytes.wrap(largeData);

        BlockItem largeItem = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(largeDataByteString)
                        .build())
                .build();

        blockState.addItem(largeItem);
        blockState.processPendingItems(2, PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES);
        org.hiero.block.api.PublishStreamRequest request = blockState.getRequest(0);

        assertThat(request).isNull();
    }

    @Test
    void testPublishStreamRequestSizeAtLimitPlusOneByte() {
        BlockState blockState = new BlockState(0);

        byte[] largeData = new byte[PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES - 19];
        Bytes largeDataByteString = Bytes.wrap(largeData);

        BlockItem largeItem = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(largeDataByteString)
                        .build())
                .build();

        blockState.addItem(largeItem);
        blockState.processPendingItems(2, PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES);
        org.hiero.block.api.PublishStreamRequest request = blockState.getRequest(0);

        assertThat(request).isNull();
    }

    @Test
    void testPublishStreamRequestSizeLimit() {
        BlockState blockState = new BlockState(0);

        byte[] largeData = new byte[PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES / 2];
        Bytes largeDataByteString = Bytes.wrap(largeData);

        BlockItem largeItem = BlockItem.newBuilder()
                .eventTransaction(EventTransaction.newBuilder()
                        .applicationTransaction(largeDataByteString)
                        .build())
                .build();

        blockState.addItem(largeItem);
        blockState.addItem(largeItem);
        blockState.addItem(largeItem);
        blockState.processPendingItems(3, PUBLISH_STREAM_REQUEST_MAX_SIZE_BYTES);

        assertThat(blockState.numRequestsCreated()).isEqualTo(1);
    }
}
