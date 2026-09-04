// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.grpc.impl.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.workflows.clpr.ClprStreamingSyncSession;
import com.hedera.node.app.workflows.clpr.ClprSyncWorkflow;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.BlockingClientCall;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test for the CLPR streaming sync endpoint (server-side).
 * <p>
 * It starts a generic netty GRPC server and uses {@link NettyGrpcServerManager} to register the streaming service on
 * it. Then, it emulates sync rounds between a client (this JUnit test) and the server (CLPR Endpoint).
 * <p>
 * This test covers the streaming sync endpoint registration and the streaming request lifecycle (with mocked session),
 * ensuring the endpoint is correctly configured. The components covered are:
 * <ul>
 *     <li>NettyGrpcServerManager (Endpoint registration)</li>
 *     <li>ClprStreamingSyncMethod (the RPC method handler)</li>
 *     <li>DataBufferMarshaller (every message round-trips through it, at production size limits)</li>
 * </ul>
 */
class ClprStreamingSyncServerTest {

    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");
    private static final long DEADLINE_SECONDS = 10;

    private static final MethodDescriptor.Marshaller<byte[]> BYTE_MARSHALLER = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(final byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(final InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }
    };

    private static final MethodDescriptor<byte[], byte[]> STREAMING_SYNC = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .setFullMethodName(ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME)
            .setRequestMarshaller(BYTE_MARSHALLER)
            .setResponseMarshaller(BYTE_MARSHALLER)
            .build();

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel = null;
        }
        if (server != null) {
            server.shutdownNow().awaitTermination();
            server = null;
        }
    }

    @Test
    @DisplayName("streamingSync is registered alongside the unary sync method, as BIDI_STREAMING")
    void registersStreamingMethodOnTheSyncListener() {
        // The pre-existing unary method has to survive the merge: a gRPC server keys its registry by service name, so
        // returning a second definition under that name rather than merging into this one would displace it.
        final var unarySync = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(ClprEndpointServiceDefinition.SERVICE_NAME + "/sync")
                .setRequestMarshaller(BYTE_MARSHALLER)
                .setResponseMarshaller(BYTE_MARSHALLER)
                .build();
        final var base = ServerServiceDefinition.builder(ClprEndpointServiceDefinition.SERVICE_NAME)
                .addMethod(unarySync, ServerCalls.asyncUnaryCall((ignoredRequest, ignoredObserver) -> {}))
                .build();

        final var withStreaming =
                NettyGrpcServerManager.addClprStreamingSync(base, noopWorkflow(), new DataBufferMarshaller(256, 128));

        final var method = withStreaming.getMethod(ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME);
        assertThat(method).isNotNull();
        assertThat(method.getMethodDescriptor().getType()).isEqualTo(MethodDescriptor.MethodType.BIDI_STREAMING);

        final var survivor = withStreaming.getMethod(unarySync.getFullMethodName());
        assertThat(survivor).isNotNull();
        assertThat(survivor.getMethodDescriptor().getType()).isEqualTo(MethodDescriptor.MethodType.UNARY);
    }

    @Test
    @Timeout(30)
    @DisplayName("the server answers every message of the four-message cycle instead of closing after the first")
    void drivesTheFourMessageCycle() throws Exception {
        final var session = mock(ClprStreamingSyncSession.class);
        final Deque<ClprStreamingSyncPayload> replies =
                new ArrayDeque<>(List.of(payload(request(3L), Bytes.wrap("bundle-for-peer")), payload(null, null)));
        given(session.onMessage(any())).willAnswer(inv -> replies.poll());
        given(session.isComplete()).willReturn(false, true);

        final var call = startServerAndCall(session);

        // Msg 1: the peer's request, no bundle yet.
        assertThat(call.write(bytes(payload(request(0L), null)))).isTrue();

        // Msg 2: our request plus the bundle shaped for it. A unary-wired server would stop right here.
        final var second = read(call);
        assertThat(second).isNotNull();
        assertThat(second.bundleRequest().currentReceivedMessageId()).isEqualTo(3L);
        assertThat(second.bundleResponse().bundlePayload()).isEqualTo(Bytes.wrap("bundle-for-peer"));

        // Msg 3: the peer's bundle. Msg 4: our terminal message.
        assertThat(call.write(bytes(payload(null, Bytes.wrap("bundle-from-peer")))))
                .isTrue();
        final var fourth = read(call);
        assertThat(fourth).isNotNull();
        assertThat(fourth.bundleRequest()).isNull();
        assertThat(fourth.bundleResponse()).isNull();

        // Both sides are terminal, so the server closed its side: the stream drains cleanly.
        assertThat(read(call)).isNull();
    }

    @Test
    @Timeout(30)
    @DisplayName("the server keeps the stream open while it has nothing left but the peer has not finished")
    void staysOpenUntilThePeerHalfCloses() throws Exception {
        // isComplete() never true: this side went terminal but the peer has not, so closing now would drop
        // whatever the peer is still about to write.
        final var session = mock(ClprStreamingSyncSession.class);
        given(session.onMessage(any())).willAnswer(inv -> payload(null, null));
        given(session.isComplete()).willReturn(false);

        final var call = startServerAndCall(session);

        for (int i = 0; i < 3; i++) {
            assertThat(call.write(bytes(payload(null, Bytes.wrap("peer-bundle-" + i)))))
                    .as("write %d must reach a still-open server", i)
                    .isTrue();
            final ClprStreamingSyncPayload reply = read(call);
            assertThat(reply).isNotNull();
            assertThat(reply.bundleResponse()).isNull();
        }

        // Only once the peer half-closes does the server close its own side.
        call.halfClose();
        assertThat(read(call)).isNull();
    }

    @Test
    @Timeout(30)
    @DisplayName("a session error is surfaced to the peer as a gRPC status rather than a silent close")
    void surfacesSessionErrors() throws Exception {
        final var session = mock(ClprStreamingSyncSession.class);
        given(session.onMessage(any()))
                .willThrow(new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription("Channel is not eligible for sync")));

        final var call = startServerAndCall(session);
        assertThat(call.write(bytes(payload(request(0L), null)))).isTrue();

        // BlockingClientCall surfaces a peer status as the checked StatusException.
        assertThatThrownBy(() -> read(call))
                .isInstanceOf(StatusException.class)
                .hasMessageContaining("not eligible for sync");
    }

    @Test
    @Timeout(30)
    @DisplayName("when CLPR is not enabled, the server rejects the call")
    void clprIsNotEnabled() throws Exception {
        // CLPR disabled is decided at stream open, so the peer is told immediately rather than after its first write.
        final var workflow = workflowRefusingWith(
                new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled")));
        final var call = startServerAndCall(workflow);

        assertThatThrownBy(() -> read(call))
                .isInstanceOf(StatusException.class)
                .hasMessageContaining("CLPR is not enabled");
    }

    /** Starts a plaintext server carrying the streaming method as registered in production, and opens a call. */
    private BlockingClientCall<byte[], byte[]> startServerAndCall(final ClprStreamingSyncSession session)
            throws Exception {
        return startServerAndCall(workflowReturning(session));
    }

    private BlockingClientCall<byte[], byte[]> startServerAndCall(final ClprSyncWorkflow workflow) throws Exception {
        final var base = ServerServiceDefinition.builder(ClprEndpointServiceDefinition.SERVICE_NAME)
                .build();
        final var service = NettyGrpcServerManager.addClprStreamingSync(
                base,
                workflow,
                new DataBufferMarshaller(
                        NettyGrpcServerManager.MAX_TRANSACTION_SIZE + 1, NettyGrpcServerManager.MAX_TRANSACTION_SIZE));

        server = NettyServerBuilder.forPort(0).addService(service).build().start();
        channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
                .usePlaintext()
                .build();
        return ClientCalls.blockingBidiStreamingCall(
                channel, STREAMING_SYNC, CallOptions.DEFAULT.withDeadlineAfter(DEADLINE_SECONDS, TimeUnit.SECONDS));
    }

    @Nullable
    private static ClprStreamingSyncPayload read(final BlockingClientCall<byte[], byte[]> call) throws Exception {
        final var response = call.read();
        return response == null ? null : ClprStreamingSyncPayload.PROTOBUF.parse(Bytes.wrap(response));
    }

    private static byte[] bytes(final ClprStreamingSyncPayload payload) {
        return ClprStreamingSyncPayload.PROTOBUF.toBytes(payload).toByteArray();
    }

    private static ClprStreamingSyncPayload payload(
            @Nullable final ClprBundleRequest request, @Nullable final Bytes bundle) {
        return ClprStreamingSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundleRequest(request)
                .bundleResponse(
                        bundle == null
                                ? null
                                : ClprBundleResponse.newBuilder()
                                        .bundlePayload(bundle)
                                        .build())
                .build();
    }

    private static ClprBundleRequest request(final long currentReceivedMessageId) {
        return ClprBundleRequest.newBuilder()
                .currentReceivedMessageId(currentReceivedMessageId)
                .build();
    }

    private static ClprSyncWorkflow workflowReturning(final ClprStreamingSyncSession session) {
        return new ClprSyncWorkflow() {
            @Override
            public void handleSync(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void handleDiscovery(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
                throw new UnsupportedOperationException();
            }

            @NonNull
            @Override
            public ClprStreamingSyncSession openStreamingSync() {
                return session;
            }
        };
    }

    /** A workflow that rejects every attempt to open a session. */
    private static ClprSyncWorkflow workflowRefusingWith(final RuntimeException rejection) {
        return new ClprSyncWorkflow() {
            @Override
            public void handleSync(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void handleDiscovery(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
                throw new UnsupportedOperationException();
            }

            @NonNull
            @Override
            public ClprStreamingSyncSession openStreamingSync() {
                throw rejection;
            }
        };
    }

    private static ClprSyncWorkflow noopWorkflow() {
        return workflowReturning(mock(ClprStreamingSyncSession.class));
    }
}
