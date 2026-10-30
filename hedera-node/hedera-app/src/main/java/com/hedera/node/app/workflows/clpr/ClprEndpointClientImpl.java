// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.clpr.ClprEndpointServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * gRPC-backed {@link ClprEndpointClient}. Uses Netty for HTTP/2 transport and grpc-java
 * {@link ClientCalls} for the bidirectional-streaming {@code sync}.
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

    /** The grpc-java method descriptor for the bidirectional-streaming {@code sync} RPC. */
    private static final MethodDescriptor<byte[], byte[]> SYNC_METHOD_DESCRIPTOR =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                    .setFullMethodName(ClprEndpointServiceDefinition.SYNC_FULL_METHOD_NAME)
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
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     */
    ClprEndpointClientImpl(@NonNull final String host, final int port) {
        this(host, port, false);
    }

    /**
     * Creates a client targeting the given peer address.
     *
     * <p>When {@code useTls} is {@code true}, the channel is encrypted with TLS against the peer's TLS
     * port. The peer's server certificate is <b>not</b> verified: TLS here provides confidentiality only, and
     * the authenticity of everything exchanged over {@code sync} comes from the state proofs carried in the
     * payload, which the receiving ledger verifies. When {@code useTls} is {@code false}, the channel is
     * plaintext — expected during local development and in test networks without a TLS certificate.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port (the TLS port when {@code useTls}, the plaintext port otherwise)
     * @param useTls whether to encrypt the channel with TLS
     */
    ClprEndpointClientImpl(@NonNull final String host, final int port, final boolean useTls) {
        this.channel = newChannel(host, port, useTls);
    }

    /**
     * Builds the {@link ManagedChannel} backing this client, using the same TLS/plaintext rules as the
     * constructors.
     *
     * @param host the peer's IP address
     * @param port the peer's gRPC port
     * @param useTls whether to encrypt the channel with TLS
     * @return a new managed channel owned by this client instance
     */
    @NonNull
    static ManagedChannel newChannel(@NonNull final String host, final int port, final boolean useTls) {
        requireNonNull(host);
        if (!useTls) {
            return NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
        }
        try {
            return NettyChannelBuilder.forAddress(host, port)
                    .sslContext(buildClientSslContext())
                    .build();
        } catch (final SSLException e) {
            throw new ClientException(
                    "Failed to build TLS channel for CLPR peer " + host + ":" + port + ": " + e.getMessage(), e);
        }
    }

    /**
     * Builds the client-side {@link SslContext} for the TLS sync channel. It trusts any server certificate and
     * skips hostname verification: peers are dialed by IP address and their TLS certificates are
     * operator-provisioned, so there is no trust anchor to validate them against (see the constructor for why
     * this is acceptable).
     */
    @NonNull
    static SslContext buildClientSslContext() throws SSLException {
        return GrpcSslContexts.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .endpointIdentificationAlgorithm(null)
                .build();
    }

    @Override
    @NonNull
    public ClprStreamingSyncCall sync(@NonNull final Duration timeout) {
        requireNonNull(timeout);
        final var callOptions = CallOptions.DEFAULT.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
        final var call = ClientCalls.blockingBidiStreamingCall(channel, SYNC_METHOD_DESCRIPTOR, callOptions);
        logger.debug("Streaming sync initiated with peer {}", channel.authority());
        return new ClprStreamingSyncCall(call, channel.authority());
    }

    /**
     * Shuts down the channel this client owns. Package-private and absent from the
     * {@link ClprEndpointClient} interface: only {@link ClprEndpointClientCache} — the sole holder of a
     * reference typed to this concrete class — may tear a client down, on a transport change or node stop.
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
