// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsResponse;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprServiceEndpoint;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.workflows.clpr.ClprEndpointClient.ClprSyncException;
import com.hedera.pbj.runtime.UnknownFieldException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ClprEndpointClient} establishes a mutually-authenticated TLS channel against a
 * peer using the two-tier cert model from spec §4.4: the peer's ECDSA P-384 CA cert is pinned from
 * on-chain bytes, and the peer's leaf cert is validated by verifying it was signed by that CA.
 *
 * <p>Tests spin up a real Netty gRPC server so a successful round-trip proves both directions
 * of the mTLS handshake actually work.
 */
class ClprEndpointClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** How long to wait for the peer to observe a cancellation — well under {@link #TIMEOUT}, so a call left
     * pinned until its deadline fails this wait rather than satisfying it. */
    private static final long CANCEL_TIMEOUT_SECONDS = 5;

    private static final Bytes CHANNEL_ID =
            Bytes.fromHex("01000000000000000000000000000000000000000000000000000000000000ab");

    /** Shared CA that signs both the server leaf and the client leaf; its cert is pinned by peers. */
    private static ClprTestCa testCa;

    /** Server leaf, signed by {@link #testCa}. */
    private static ClprTestCa.Leaf serverLeaf;

    /** Client leaf, signed by {@link #testCa}. */
    private static ClprTestCa.Leaf clientLeaf;

    /** A second, unrelated CA — used to test that the wrong CA is rejected. */
    private static X509Certificate wrongCaCert;

    /** A simple byte-array marshaller mirroring the one used by {@link ClprEndpointClient}. */
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

    private Server server;

    @BeforeAll
    static void generateTestCerts() throws Exception {
        // The CA carries basicConstraints:CA:TRUE so the server's default PKIX trust manager accepts it
        // as the anchor for the client leaf; leaves carry none so they validate as leaves.
        testCa = new ClprTestCa("test-clpr-ca");
        serverLeaf = testCa.signLeaf("test-server-leaf");
        clientLeaf = testCa.signLeaf("test-client-leaf");
        // A second, unrelated CA — pinning this should cause chain validation to fail.
        wrongCaCert = new ClprTestCa("wrong-clpr-ca").caCert();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.shutdownNow().awaitTermination();
            server = null;
        }
    }

    @Nested
    class StreamingSync {

        @Test
        void rejectsResponseWithUnknownFields() throws Exception {
            final var bytes = ClprStreamingSyncPayload.PROTOBUF
                    .toBytes(buildStreamingSyncPayload(null, null))
                    .append(Bytes.fromHex("c03e01"))
                    .toByteArray();
            final int port = startStreamingServer(observer -> new StreamObserver<>() {
                @Override
                public void onNext(final byte[] value) {
                    observer.onNext(bytes);
                    observer.onCompleted();
                }

                @Override
                public void onError(final Throwable error) {}

                @Override
                public void onCompleted() {}
            });

            final var client = newClient(port, testCa.caCert());
            try (final var call = client.sync(TIMEOUT)) {
                call.write(buildStreamingSyncPayload(0, null));
                call.halfClose();
                assertThatThrownBy(call::read)
                        .isInstanceOf(ClprSyncException.class)
                        .hasRootCauseInstanceOf(UnknownFieldException.class);
            } finally {
                client.shutdownChannel();
            }
        }

        @Test
        @DisplayName("sync drives a multi-message exchange")
        void syncSucceedsOverMutualTls() throws Exception {
            final var mockedResponseMessages = new ClprStreamingSyncPayload[] {
                buildStreamingSyncPayload(9, Bytes.wrap("1st response bundle")), buildStreamingSyncPayload(null, null),
            };
            final var received = new CopyOnWriteArrayList<ClprStreamingSyncPayload>();

            final int port = startStreamingServer(mockedResponseMessages, received);

            final var client = newClient(port, testCa.caCert());
            try (final var call = client.sync(TIMEOUT)) {
                // Msg 1: this ledger sends a bundle request to the peer ledger.
                call.write(buildStreamingSyncPayload(8, null));

                // Msg 2: peer's reply with a bundle + your own bundle request.
                final var response = call.read();
                assertThat(response).isNotNull();
                assertThat(response.channelId()).isEqualTo(CHANNEL_ID);
                assertThat(response.bundleRequest()).isEqualTo(mockedResponseMessages[0].bundleRequest());
                assertThat(response.bundleResponse()).isEqualTo(mockedResponseMessages[0].bundleResponse());

                // Msg 3: this ledger sends its first bundle, without a request.
                call.write(buildStreamingSyncPayload(null, Bytes.wrap("2nd message: bundle response")));
                call.halfClose();

                // Msg 4: peer ledger has nothing to send and closes the stream.
                final var terminal = call.read();
                assertThat(terminal).isNotNull();
                assertThat(terminal.channelId()).isEqualTo(CHANNEL_ID);
                assertThat(terminal.bundleRequest()).isNull();
                assertThat(terminal.bundleResponse()).isNull();

                // The stream is now drained: the next read observes the peer's clean close.
                assertThat(call.read()).isNull();
            } finally {
                client.shutdownChannel();
            }

            // What the peer actually received off the wire, proving write() serialized both messages correctly and that
            // bundle_request really is one-shot per side rather than repeated on every message.
            assertThat(received).hasSize(2);
            assertThat(received.get(0).channelId()).isEqualTo(CHANNEL_ID);
            assertThat(received.get(0).bundleRequest()).isNotNull();
            assertThat(received.get(0).bundleRequest().currentReceivedMessageId())
                    .isEqualTo(8L);
            assertThat(received.get(0).bundleResponse()).isNull();
            assertThat(received.get(1).channelId()).isEqualTo(CHANNEL_ID);
            assertThat(received.get(1).bundleRequest()).isNull();
            assertThat(received.get(1).bundleResponse().bundlePayload())
                    .isEqualTo(Bytes.wrap("2nd message: bundle response"));
        }

        @Test
        @DisplayName("sync write fails on closed the stream")
        void syncWriteRejectedAfterPeerCloses() throws Exception {
            // A peer running the old unary-only server: it answers the first message and closes with OK. The second
            // write must not be silently dropped, or a truncated exchange would look completely healthy.
            final var mockedResponseMessages = new ClprStreamingSyncPayload[] {buildStreamingSyncPayload(null, null)};
            final int port = startStreamingServer(mockedResponseMessages, new CopyOnWriteArrayList<>());

            final var client = newClient(port, testCa.caCert());
            try (final var call = client.sync(TIMEOUT)) {
                call.write(buildStreamingSyncPayload(8, null));

                // Drain until the peer's clean close is observed, so the next write is provably a no-op at the grpc
                // layer.
                while (call.read() != null) {
                    // keep reading
                }

                assertThatThrownBy(() -> call.write(buildStreamingSyncPayload(null, Bytes.wrap("dropped bundle"))))
                        .isInstanceOf(ClprSyncException.class)
                        .hasMessageContaining("peer closed the stream");
            } finally {
                client.shutdownChannel();
            }
        }

        @Test
        @DisplayName("sync surfaces a peer error status as a ClprSyncException")
        void syncReadSurfacesPeerError() throws Exception {
            final int port = startStreamingServer(observer -> new StreamObserver<>() {
                @Override
                public void onNext(final byte[] value) {
                    observer.onError(Status.INTERNAL
                            .withDescription("verifier contract reverted")
                            .asRuntimeException());
                }

                @Override
                public void onError(final Throwable t) {
                    // no-op
                }

                @Override
                public void onCompleted() {
                    // no-op
                }
            });

            final var client = newClient(port, testCa.caCert());
            try (final var call = client.sync(TIMEOUT)) {
                call.write(buildStreamingSyncPayload(8, null));

                assertThatThrownBy(call::read)
                        .isInstanceOf(ClprSyncException.class)
                        .hasMessageContaining("Streaming sync read failed");
            } finally {
                client.shutdownChannel();
            }
        }

        @Test
        @DisplayName("sync halfClose, cancel and close are idempotent")
        void syncTerminationIsIdempotent() throws Exception {
            final var mockedResponseMessages = new ClprStreamingSyncPayload[] {buildStreamingSyncPayload(null, null)};
            final int port = startStreamingServer(mockedResponseMessages, new CopyOnWriteArrayList<>());

            final var client = newClient(port, testCa.caCert());
            try {
                final var call = client.sync(TIMEOUT);
                call.write(buildStreamingSyncPayload(8, null));

                // grpc's BlockingClientCall throws IllegalStateException on a repeated halfClose; the wrapper must not.
                assertThatNoException().isThrownBy(() -> {
                    call.halfClose();
                    call.halfClose();
                    call.cancel("first", null);
                    call.cancel("second", null);
                    call.close();
                    call.close();
                });
            } finally {
                client.shutdownChannel();
            }
        }

        @Test
        @DisplayName("sync close cancels an exchange abandoned part-way")
        void syncCloseReleasesAbandonedExchange() throws Exception {
            final var cancelObservedByPeer = new CountDownLatch(1);
            final int port = startStreamingServer(responseObserver -> {
                ((ServerCallStreamObserver<byte[]>) responseObserver)
                        .setOnCancelHandler(cancelObservedByPeer::countDown);
                return new StreamObserver<>() {
                    @Override
                    public void onNext(final byte[] value) {
                        // deliberately unanswered: the peer is mid-exchange when the client walks out
                    }

                    @Override
                    public void onError(final Throwable t) {
                        // no-op
                    }

                    @Override
                    public void onCompleted() {
                        // no-op
                    }
                };
            });

            final var client = newClient(port, testCa.caCert());
            try {
                final ClprStreamingSyncCall abandonedCall;
                try (final var call = client.sync(TIMEOUT)) {
                    abandonedCall = call;
                    call.write(buildStreamingSyncPayload(8, null));
                    // Walk out mid-exchange without half-closing.
                    // After leaving this block, close() is called on the streaming call.
                }

                // The peer saw the stream torn down on the way out rather than left pinned until the deadline.
                assertThat(cancelObservedByPeer.await(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isTrue();

                // And the handle itself is spent, so a caller that keeps going gets an exception instead of a silent
                // drop.
                assertThatThrownBy(() -> abandonedCall.write(buildStreamingSyncPayload(null, Bytes.wrap("too late"))))
                        .isInstanceOf(ClprSyncException.class);
            } finally {
                client.shutdownChannel();
            }
        }
    }

    @Test
    @DisplayName("discoverEndpoints round-trips over mTLS")
    void discoverSucceedsOverMutualTls() throws Exception {
        final var peerEndpoint = ClprEndpoint.newBuilder()
                .serviceEndpoint(ClprServiceEndpoint.newBuilder()
                        .ipAddress("10.0.0.9")
                        .port(50211)
                        .build())
                .build();
        final var discoverResponse = ClprDiscoverEndpointsResponse.newBuilder()
                .endpoints(List.of(peerEndpoint))
                .build();
        final int port = startServer(
                ClprDiscoverEndpointsResponse.PROTOBUF.toBytes(discoverResponse).toByteArray());

        final var client = newClient(port, testCa.caCert());
        try {
            final var discovered = client.discoverEndpoints(CHANNEL_ID, TIMEOUT);

            assertThat(discovered).containsExactly(peerEndpoint);
        } finally {
            client.shutdownChannel();
        }
    }

    @Test
    void rejectsDiscoverResponseWithUnknownFields() throws Exception {
        final var discoverResponse = ClprDiscoverEndpointsResponse.PROTOBUF
                .toBytes(ClprDiscoverEndpointsResponse.DEFAULT)
                .append(Bytes.fromHex("c03e01"));
        final int port = startServer(discoverResponse.toByteArray());

        final var client = newClient(port, testCa.caCert());
        try {
            assertThatThrownBy(() -> client.discoverEndpoints(CHANNEL_ID, TIMEOUT))
                    .isInstanceOf(ClprEndpointClient.ClprDiscoveryException.class)
                    .hasRootCauseInstanceOf(UnknownFieldException.class);
        } finally {
            client.shutdownChannel();
        }
    }

    @Test
    @DisplayName("discoverEndpoints fails when pinning a CA that did not sign the peer's leaf")
    void discoverFailsWhenPeerCertificateIsNotPinned() throws Exception {
        final int port = startServer(new byte[0]);

        // Pin a CA cert unrelated to the CA that signed the server's leaf — chain validation fails.
        final var client = newClient(port, wrongCaCert);
        try {
            assertThatThrownBy(() -> client.discoverEndpoints(CHANNEL_ID, TIMEOUT))
                    .isInstanceOf(ClprEndpointClient.ClprDiscoveryException.class);
        } finally {
            client.shutdownChannel();
        }
    }

    @Test
    @DisplayName("building the SSL context rejects malformed peer CA certificate bytes")
    void buildSslContextRejectsMalformedCertificate() {
        assertThatThrownBy(() -> ClprEndpointClientImpl.buildClientSslContext(
                        Bytes.wrap(new byte[] {1, 2, 3, 4}),
                        new ClprLeafCredentials(clientLeaf.cert(), clientLeaf.privateKey())))
                .isInstanceOf(SSLException.class)
                .hasMessageContaining("peer TLS certificate");
    }

    @Test
    @DisplayName("plaintext client constructs with a null peer certificate (no mTLS)")
    void plaintextClientAcceptsNullCertificate() {
        // clientCredentials == null → plaintext path; the peer cert is unused and may be null.
        assertThatNoException().isThrownBy(() -> new ClprEndpointClientImpl("localhost", 50211).shutdownChannel());
    }

    /**
     * Starts a Netty gRPC server that terminates TLS with the test server leaf certificate,
     * requires client authentication, and trusts the test CA cert (which signed the client leaf).
     */
    private int startServer(final byte[] discoverResponse) throws Exception {
        final var sslContext = GrpcSslContexts.configure(
                        SslContextBuilder.forServer(serverLeaf.privateKey(), serverLeaf.cert())
                                .clientAuth(ClientAuth.REQUIRE)
                                .trustManager(testCa.caCert()))
                .protocols("TLSv1.2", "TLSv1.3")
                .build();

        final var service = ServerServiceDefinition.builder("proto.ClprEndpointService")
                .addMethod(unaryMethod("discoverEndpoints"), cannedResponse(discoverResponse))
                .build();

        server = NettyServerBuilder.forPort(0)
                .sslContext(sslContext)
                .addService(service)
                .build()
                .start();
        return server.getPort();
    }

    private static MethodDescriptor<byte[], byte[]> unaryMethod(final String methodName) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName("proto.ClprEndpointService", methodName))
                .setRequestMarshaller(BYTE_MARSHALLER)
                .setResponseMarshaller(BYTE_MARSHALLER)
                .build();
    }

    private static io.grpc.ServerCallHandler<byte[], byte[]> cannedResponse(final byte[] response) {
        return ServerCalls.asyncUnaryCall((request, responseObserver) -> {
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        });
    }

    /**
     * Constructs a ClprStreamingSyncPayload object using the provided message ID and bundle payload.
     * If the message ID is null, the bundle request will also be null.
     * If the bundle payload is null, the bundle response will also be null.
     */
    private static ClprStreamingSyncPayload buildStreamingSyncPayload(
            @Nullable final Integer receivedMessageId, @Nullable final Bytes bundlePayload) {
        final var bundleRequest = receivedMessageId == null
                ? null
                : ClprBundleRequest.newBuilder()
                        .currentReceivedMessageId(receivedMessageId)
                        .build();
        final var bundleResponse = bundlePayload == null
                ? null
                : ClprBundleResponse.newBuilder().bundlePayload(bundlePayload).build();
        return ClprStreamingSyncPayload.newBuilder()
                .channelId(CHANNEL_ID)
                .bundleRequest(bundleRequest)
                .bundleResponse(bundleResponse)
                .build();
    }

    /**
     * Starts a Netty gRPC server exposing only the bidi-streaming sync RPC, with the same mTLS setup
     * as {@link #startServer}. Replies to the client's messages with the provided {@code responseMessages}
     * in the sequence they are provided, recording every message the client sent into {@code received}.
     * Once the last response has been sent the server closes its side with OK; any further client message
     * is recorded but not answered.
     */
    private int startStreamingServer(
            final ClprStreamingSyncPayload[] responseMessages, final List<ClprStreamingSyncPayload> received)
            throws Exception {
        return startStreamingServer(exchangeMessages(responseMessages, received));
    }

    /** Starts a Netty gRPC server exposing only the bidi-streaming sync RPC, backed by the given handler. */
    private int startStreamingServer(final ServerCalls.BidiStreamingMethod<byte[], byte[]> handler) throws Exception {
        final var sslContext = GrpcSslContexts.configure(
                        SslContextBuilder.forServer(serverLeaf.privateKey(), serverLeaf.cert())
                                .clientAuth(ClientAuth.REQUIRE)
                                .trustManager(testCa.caCert()))
                .protocols("TLSv1.2", "TLSv1.3")
                .build();

        final var service = ServerServiceDefinition.builder(ClprEndpointServiceDefinition.SERVICE_NAME)
                .addMethod(
                        streamingMethod(ClprEndpointServiceDefinition.SYNC_FULL_METHOD_NAME),
                        ServerCalls.asyncBidiStreamingCall(handler))
                .build();

        server = NettyServerBuilder.forPort(0)
                .sslContext(sslContext)
                .addService(service)
                .build()
                .start();
        return server.getPort();
    }

    private static MethodDescriptor<byte[], byte[]> streamingMethod(final String fullMethodName) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName(fullMethodName)
                .setRequestMarshaller(BYTE_MARSHALLER)
                .setResponseMarshaller(BYTE_MARSHALLER)
                .build();
    }

    /**
     * Records every client message into {@code received} and answers each one with the next of
     * {@code responseMessages}, closing the stream with OK after the last. Messages arriving after that are still
     * recorded but go unanswered — which is exactly the truncated-exchange shape an old unary-only peer produces.
     * gRPC serializes {@code onNext} per call, so the index needs no synchronization.
     */
    private static ServerCalls.BidiStreamingMethod<byte[], byte[]> exchangeMessages(
            final ClprStreamingSyncPayload[] responseMessages, final List<ClprStreamingSyncPayload> received) {
        return responseObserver -> new StreamObserver<>() {
            private int nextMessageIdx = 0;

            @Override
            public void onNext(final byte[] value) {
                try {
                    received.add(ClprStreamingSyncPayload.PROTOBUF.parse(Bytes.wrap(value)));
                } catch (final Exception e) {
                    throw new AssertionError("Client sent an unparseable ClprStreamingSyncPayload", e);
                }
                if (nextMessageIdx >= responseMessages.length) {
                    return;
                }
                final var nextMessageAsBytes = ClprStreamingSyncPayload.PROTOBUF
                        .toBytes(responseMessages[nextMessageIdx])
                        .toByteArray();
                responseObserver.onNext(nextMessageAsBytes);

                if (++nextMessageIdx == responseMessages.length) {
                    responseObserver.onCompleted();
                }
            }

            @Override
            public void onError(final Throwable t) {
                // no-op: test server does not need to react to client-side errors
            }

            @Override
            public void onCompleted() {
                // client half-closed; the last onNext already sent the terminal message and closed this side.
            }
        };
    }

    private ClprEndpointClientImpl newClient(final int port, final X509Certificate pinnedCaCert) throws Exception {
        // Encode the CA cert as DER bytes (what's stored on-chain in ClprEndpoint.tls_certificate)
        final var caDerBytes = Bytes.wrap(pinnedCaCert.getEncoded());
        return new ClprEndpointClientImpl(
                "localhost", port, caDerBytes, new ClprLeafCredentials(clientLeaf.cert(), clientLeaf.privateKey()));
    }
}
