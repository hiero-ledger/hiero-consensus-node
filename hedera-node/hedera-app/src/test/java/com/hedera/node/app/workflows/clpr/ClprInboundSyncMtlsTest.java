// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static io.netty.handler.ssl.SupportedCipherSuiteFilter.INSTANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.workflows.clpr.mtls.ClprMtlsContexts;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hiero.base.crypto.CertificateUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies the CLPR mTLS sync listener end-to-end against the <b>production</b> TLS wiring
 * ({@link ClprMtlsContexts}): both sides present the real ephemeral <b>Ed25519</b> leaf over the
 * BouncyCastle JSSE provider, the server requires client auth ({@link ClientAuth#REQUIRE}), and a
 * connecting client's leaf is pinned against the known-peer-CA set.
 *
 * <p>A successful round-trip proves the Ed25519-leaf handshake actually completes through the same
 * code path production uses; the negative cases prove the trust manager rejects an untrusted CA, a
 * missing client cert, and a CA cert presented as a leaf.
 */
class ClprInboundSyncMtlsTest {

    private static final String SERVICE = "proto.ClprEndpointService";
    private static final byte[] SYNC_RESPONSE = Bytes.wrap("inbound-ok").toByteArray();
    private static final List<String> SUPPORTED_CIPHERS = List.of(
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_AES_256_GCM_SHA384");
    private static final String[] SUPPORTED_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

    /** Peer CA that signs the connecting client's leaf — the one the server should trust. */
    private static ClprTestCa peerCa;

    /** The server's own CA; the client pins {@code serverCa.caCert()} to trust the server's leaf. */
    private static ClprTestCa serverCa;

    /** Server leaf (Ed25519), signed by {@link #serverCa}. */
    private static ClprLeafCredentials serverLeaf;

    /** Client leaf (Ed25519), signed by {@link #peerCa}. */
    private static ClprLeafCredentials clientLeaf;

    /** A CA the server does not trust. */
    private static X509Certificate untrustedCaCert;

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

    private static final MethodDescriptor<byte[], byte[]> SYNC_METHOD = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, "sync"))
            .setRequestMarshaller(BYTE_MARSHALLER)
            .setResponseMarshaller(BYTE_MARSHALLER)
            .build();

    private Server server;

    @BeforeAll
    static void generateCerts() throws Exception {
        ClprMtlsContexts.ensureProvidersRegistered();
        peerCa = new ClprTestCa("peer-clpr-ca");
        serverCa = new ClprTestCa("server-clpr-ca");
        serverLeaf = ed25519Leaf(serverCa, "clpr-leaf-server");
        clientLeaf = ed25519Leaf(peerCa, "clpr-leaf-client");
        untrustedCaCert = new ClprTestCa("untrusted-clpr-ca").caCert();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.shutdownNow().awaitTermination();
            server = null;
        }
    }

    @Test
    @DisplayName("the ephemeral leaf presented is an Ed25519 certificate")
    void leafIsEd25519() {
        assertThat(serverLeaf.certificate().getPublicKey().getAlgorithm()).isEqualTo("Ed25519");
        assertThat(clientLeaf.certificate().getPublicKey().getAlgorithm()).isEqualTo("Ed25519");
    }

    @Test
    @Timeout(30)
    @DisplayName("sync succeeds when the Ed25519 client leaf is signed by a known peer CA")
    void acceptsClientLeafSignedByKnownPeerCa() throws Exception {
        final int port = startServer(() -> groupByIssuer(peerCa.caCert()));
        final var channel = clientChannel(port, clientLeaf);
        try {
            final var response = ClientCalls.blockingUnaryCall(
                    channel, SYNC_METHOD, deadline(), Bytes.wrap("hello").toByteArray());
            assertThat(response).isEqualTo(SYNC_RESPONSE);
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("sync is rejected when the client leaf is signed by an untrusted CA")
    void rejectsClientLeafSignedByUntrustedCa() throws Exception {
        final int port = startServer(() -> groupByIssuer(untrustedCaCert));
        final var channel = clientChannel(port, clientLeaf);
        try {
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                            channel,
                            SYNC_METHOD,
                            deadline(),
                            Bytes.wrap("hello").toByteArray()))
                    .isInstanceOf(StatusRuntimeException.class);
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("sync is rejected when the client presents no certificate (ClientAuth.REQUIRE)")
    void rejectsClientWithNoCertificate() throws Exception {
        final int port = startServer(() -> groupByIssuer(peerCa.caCert()));
        final var channel = noCertClientChannel(port);
        try {
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                            channel,
                            SYNC_METHOD,
                            deadline(),
                            Bytes.wrap("hello").toByteArray()))
                    .isInstanceOf(StatusRuntimeException.class);
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("sync is rejected when a CA cert is presented as the client leaf")
    void rejectsCaCertPresentedAsLeaf() throws Exception {
        // The peer CA cert is self-signed and would "verify under" itself, but it is a CA cert, so the
        // leaf-shape check must reject it before signature verification.
        final int port = startServer(() -> groupByIssuer(peerCa.caCert()));
        final var caAsLeaf =
                new ClprLeafCredentials(peerCa.caCert(), peerCa.caKeyPair().getPrivate());
        final var channel = clientChannel(port, caAsLeaf);
        try {
            assertThatThrownBy(() -> ClientCalls.blockingUnaryCall(
                            channel,
                            SYNC_METHOD,
                            deadline(),
                            Bytes.wrap("hello").toByteArray()))
                    .isInstanceOf(StatusRuntimeException.class);
        } finally {
            channel.shutdownNow();
        }
    }

    /** Starts a Netty server using the production {@link ClprMtlsContexts#serverContext} wiring. */
    private int startServer(final Supplier<Map<X500Principal, List<X509Certificate>>> peerCasByIssuer)
            throws Exception {
        final var service = ServerServiceDefinition.builder(SERVICE)
                .addMethod(SYNC_METHOD, ServerCalls.asyncUnaryCall((request, responseObserver) -> {
                    responseObserver.onNext(SYNC_RESPONSE);
                    responseObserver.onCompleted();
                }))
                .build();
        server = NettyServerBuilder.forPort(0)
                .sslContext(ClprMtlsContexts.serverContext(serverLeaf, peerCasByIssuer))
                .addService(service)
                .build()
                .start();
        return server.getPort();
    }

    private static Map<X500Principal, List<X509Certificate>> groupByIssuer(final X509Certificate... cas) {
        final var index = new HashMap<X500Principal, List<X509Certificate>>();
        for (final var ca : cas) {
            index.computeIfAbsent(ca.getSubjectX500Principal(), k -> new ArrayList<>())
                    .add(ca);
        }
        return index;
    }

    /**
     * Builds an ephemeral Ed25519 leaf signed by {@code ca}, mirroring {@code ClprLeafCertManager}:
     * BC-provider Ed25519 key, signed by the CA with {@code SHA384withECDSA}.
     */
    private static ClprLeafCredentials ed25519Leaf(final ClprTestCa ca, final String cn) throws Exception {
        final var leafKeyPair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
                .generateKeyPair();
        final var caCert = ca.caCert();
        final var leafCert = CertificateUtils.generateCertificate(
                CertificateUtils.distinguishedName(cn),
                leafKeyPair,
                caCert.getSubjectX500Principal().getName(),
                new KeyPair(caCert.getPublicKey(), ca.caKeyPair().getPrivate()),
                SecureRandom.getInstanceStrong(),
                "SHA384withECDSA");
        return new ClprLeafCredentials(leafCert, leafKeyPair.getPrivate());
    }

    /** Client channel presenting {@code leaf} and pinning the server's CA, via {@link ClprMtlsContexts}. */
    private ManagedChannel clientChannel(final int port, final ClprLeafCredentials leaf) throws Exception {
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(ClprMtlsContexts.clientContext(leaf, serverCa.caCert()))
                .build();
    }

    /**
     * Client channel that presents <em>no</em> certificate, to exercise {@link ClientAuth#REQUIRE}.
     * Trusts (pins) the server CA and can verify the server's Ed25519 leaf, but offers no client
     * identity, so the server must reject it at the handshake.
     */
    private ManagedChannel noCertClientChannel(final int port) throws Exception {
        ClprMtlsContexts.ensureProvidersRegistered();
        final var ctx = SSLContext.getInstance("TLS", "BCJSSE");
        ctx.init(
                null,
                new TrustManager[] {ClprMtlsTrust.pinnedServerTrustManager(serverCa.caCert())},
                new SecureRandom());
        final SslContext nettyCtx = new JdkSslContext(
                ctx,
                true,
                SUPPORTED_CIPHERS,
                INSTANCE,
                new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2),
                ClientAuth.NONE,
                SUPPORTED_PROTOCOLS,
                false);
        return NettyChannelBuilder.forAddress("localhost", port)
                .sslContext(nettyCtx)
                .build();
    }

    private static CallOptions deadline() {
        return CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS);
    }
}
