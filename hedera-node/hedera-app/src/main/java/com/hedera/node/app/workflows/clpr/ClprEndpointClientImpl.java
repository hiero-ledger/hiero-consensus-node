// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsRequest;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsResponse;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import com.hedera.node.app.workflows.clpr.mtls.ClprMtlsContexts;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * gRPC-backed {@link ClprEndpointClient}. Uses Netty for HTTP/2 transport and grpc-java
 * {@link ClientCalls} for both the unary calls and the bidirectional-streaming {@code streamingSync}.
 *
 * <p>Each instance targets a single peer endpoint address and owns the {@link ManagedChannel} it
 * builds for the lifetime of the instance.
 */
class ClprEndpointClientImpl implements ClprEndpointClient {
    private static final Logger logger = LogManager.getLogger(ClprEndpointClientImpl.class);

    /** How long to wait for in-flight calls to drain when shutting the channel down. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    /** The gRPC service name. */
    private static final String SERVICE_NAME = ClprEndpointServiceDefinition.SERVICE_NAME;

    /** The sync method name within the service. */
    private static final String SYNC_METHOD = "sync";

    /** The discoverEndpoints method name within the service. */
    private static final String DISCOVER_METHOD = "discoverEndpoints";

    /** A simple byte-array marshaller for protobuf-encoded messages. */
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
                throw new RuntimeException("Failed to read gRPC response", e);
            }
        }
    };

    /** The grpc-java method descriptor for the sync RPC. */
    private static final MethodDescriptor<byte[], byte[]> SYNC_METHOD_DESCRIPTOR =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, SYNC_METHOD))
                    .setRequestMarshaller(BYTE_MARSHALLER)
                    .setResponseMarshaller(BYTE_MARSHALLER)
                    .build();

    /** The grpc-java method descriptor for the discoverEndpoints RPC. */
    private static final MethodDescriptor<byte[], byte[]> DISCOVER_METHOD_DESCRIPTOR =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE_NAME, DISCOVER_METHOD))
                    .setRequestMarshaller(BYTE_MARSHALLER)
                    .setResponseMarshaller(BYTE_MARSHALLER)
                    .build();

    /** The grpc-java method descriptor for the streamingSync RPC. */
    private static final MethodDescriptor<byte[], byte[]> STREAMING_SYNC_METHOD_DESCRIPTOR =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName(ClprEndpointServiceDefinition.STREAMING_SYNC_FULL_METHOD_NAME)
                    .setRequestMarshaller(BYTE_MARSHALLER)
                    .setResponseMarshaller(BYTE_MARSHALLER)
                    .build();

    private final Channel channel;

    /**
     * Represents an exception specific to client operations within the CLPR endpoint client module.
     * <p>
     * This exception is typically used to encapsulate errors that occur during client-side operations,
     * such as communication issues, improper payload formatting, or other unexpected conditions
     * encountered while interacting with the peer endpoint.
     */
    public static class ClientException extends RuntimeException {
        public ClientException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Constructor for a non-TLS (plain-text) client.
     * All the TLS related fields are set to null.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     */
    ClprEndpointClientImpl(@NonNull final String host, @NonNull final int port) {
        this(host, port, null, null);
    }

    /**
     * Creates a client targeting the given peer address.
     *
     * <p>When {@code clientCredentials} is non-null, the channel uses mutually-authenticated TLS:
     * the peer's ECDSA P-384 CA cert is pinned from {@code peerTlsCertificate} and the chain is
     * validated against it; this node presents its leaf identity from {@code clientCredentials}. When
     * {@code clientCredentials} is {@code null} (no CA cert configured), the channel falls back to
     * plaintext — expected during local development.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     * @param peerTlsCertificate the peer's DER-encoded ECDSA P-384 CA cert (trust anchor); required only when
     *     {@code clientCredentials != null} (mTLS), and may be {@code null} on the plaintext path (ignored)
     * @param clientCredentials this node's ephemeral leaf identity to present at handshake, or {@code null} for plaintext
     */
    ClprEndpointClientImpl(
            @NonNull final String host,
            final int port,
            @Nullable final Bytes peerTlsCertificate,
            @Nullable final ClprLeafCredentials clientCredentials) {
        this.channel = newChannel(host, port, peerTlsCertificate, clientCredentials);
    }

    /**
     * Builds the {@link ManagedChannel} backing this client, using the same mTLS/plaintext rules as the
     * constructors.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     * @param peerTlsCertificate the peer's DER-encoded CA cert; required only for mTLS, ignored for plaintext
     * @param clientCredentials this node's leaf identity for mTLS, or {@code null} for plaintext
     * @return a new managed channel owned by this client instance
     */
    @NonNull
    static ManagedChannel newChannel(
            @NonNull final String host,
            final int port,
            @Nullable final Bytes peerTlsCertificate,
            @Nullable final ClprLeafCredentials clientCredentials) {
        requireNonNull(host);
        try {
            if (clientCredentials != null) {
                requireNonNull(peerTlsCertificate, "peerTlsCertificate is required for mTLS");
                final var sslContext = buildClientSslContext(peerTlsCertificate, clientCredentials);
                return NettyChannelBuilder.forAddress(host, port)
                        .sslContext(sslContext)
                        .build();
            } else {
                return NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
            }
        } catch (final SSLException e) {
            throw new ClientException(
                    "Failed to build mTLS channel for CLPR peer " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the client-side {@link SslContext} for the mTLS sync channel.
     *
     * <p>The peer's ECDSA P-384 CA cert is parsed from {@code peerTlsCertificate} (on-chain bytes)
     * and used as the sole trust anchor: the peer's leaf is accepted only if it was signed by that CA
     * and is not itself a CA cert. This node presents its own leaf identity from
     * {@code clientCredentials}.
     */
    @NonNull
    static SslContext buildClientSslContext(
            @NonNull final Bytes peerTlsCertificate, @NonNull final ClprLeafCredentials clientCredentials)
            throws SSLException {
        final X509Certificate peerCaCert;
        try (final var in = new ByteArrayInputStream(peerTlsCertificate.toByteArray())) {
            peerCaCert =
                    (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (final Exception e) {
            throw new SSLException("Failed to parse peer TLS certificate", e);
        }
        // Built on BouncyCastle JSSE: the CLPR leaf is Ed25519, unusable as a local TLS identity on
        // SunJSSE and rejected by netty-tcnative/BoringSSL. See ClprMtlsContexts.
        return ClprMtlsContexts.clientContext(clientCredentials, peerCaCert);
    }

    @Override
    @NonNull
    public ClprSyncPayload sync(@NonNull final ClprSyncPayload request, @NonNull final Duration timeout)
            throws ClprSyncException {
        requireNonNull(request);
        requireNonNull(timeout);
        try {
            final var requestBytes = ClprSyncPayload.PROTOBUF.toBytes(request);

            final var callOptions = CallOptions.DEFAULT.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);

            final var responseBytes = ClientCalls.blockingUnaryCall(
                    channel, SYNC_METHOD_DESCRIPTOR, callOptions, requestBytes.toByteArray());

            return ClprSyncPayload.PROTOBUF.parse(Bytes.wrap(responseBytes));
        } catch (final Exception e) {
            throw new ClprSyncException("Sync call failed: " + e.getMessage(), e);
        }
    }

    @Override
    @NonNull
    public List<ClprEndpoint> discoverEndpoints(@NonNull final Bytes channelId, @NonNull final Duration timeout)
            throws ClprDiscoveryException {
        requireNonNull(channelId);
        requireNonNull(timeout);
        try {
            final var request = ClprDiscoverEndpointsRequest.newBuilder()
                    .channelId(channelId)
                    .build();
            final var requestBytes = ClprDiscoverEndpointsRequest.PROTOBUF.toBytes(request);

            final var callOptions = CallOptions.DEFAULT.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);

            final var responseBytes = ClientCalls.blockingUnaryCall(
                    channel, DISCOVER_METHOD_DESCRIPTOR, callOptions, requestBytes.toByteArray());

            final var response = ClprDiscoverEndpointsResponse.PROTOBUF.parse(Bytes.wrap(responseBytes));
            return response.endpoints();
        } catch (final Exception e) {
            throw new ClprDiscoveryException("discoverEndpoints call failed: " + e.getMessage(), e);
        }
    }

    @Override
    @NonNull
    public ClprStreamingSyncCall streamingSync(@NonNull final Duration timeout) {
        requireNonNull(timeout);
        final var callOptions = CallOptions.DEFAULT.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
        final var call = ClientCalls.blockingBidiStreamingCall(channel, STREAMING_SYNC_METHOD_DESCRIPTOR, callOptions);
        logger.debug("Streaming sync initiated with peer {}", channel.authority());
        return new ClprStreamingSyncCall(call, channel.authority());
    }

    /**
     * Shuts down the channel this client owns. Package-private and absent from the
     * {@link ClprEndpointClient} interface: only {@link ClprEndpointClientCache} — the sole holder of a
     * reference typed to this concrete class — may tear a client down, on cert rotation or node stop.
     */
    void shutdownChannel() {
        if (channel == null) {
            return;
        }
        final var managedChannel = (ManagedChannel) channel;
        try {
            managedChannel.shutdown();
            if (!managedChannel.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                managedChannel.shutdownNow();
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            managedChannel.shutdownNow();
        }
    }
}
