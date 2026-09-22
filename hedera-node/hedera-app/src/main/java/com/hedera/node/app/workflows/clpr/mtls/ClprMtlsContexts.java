// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr.mtls;

import static io.netty.handler.ssl.SupportedCipherSuiteFilter.INSTANCE;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.workflows.clpr.ClprLeafCredentials;
import com.hedera.node.app.workflows.clpr.ClprMtlsTrust;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

/**
 * Builds the Netty {@link SslContext}s for the CLPR mTLS sync listener (server) and outbound client.
 *
 * <p>Lives in this non-exported package because it returns Netty SSL types across CLPR call sites
 * within the module (the {@code clpr} package is exported, which would flag a Netty type in its API).
 *
 * <p>The CLPR leaf identity is an <b>Ed25519</b> certificate. SunJSSE cannot use an
 * Ed25519 certificate as a local TLS authentication identity, and {@code netty-tcnative}/BoringSSL
 * rejects Ed25519 private-key material — so both the server and outbound client run their TLS on the
 * <b>BouncyCastle JSSE provider</b> ({@code BCJSSE}), which does support Ed25519 leaf authentication.
 *
 * <p>Two BouncyCastle requirements are baked in here:
 * <ul>
 *   <li>The {@code BouncyCastleProvider} and {@code BouncyCastleJsseProvider} must be
 *       <em>registered</em> in {@link Security} (appended, so the JVM default TLS is unaffected):
 *       Netty's ALPN bridge resolves the provider by name ({@code SSLContext.getInstance("TLS","BCJSSE")}).</li>
 *   <li>The leaf key is fed to BC via a {@code BC} {@code PKCS12} keystore so it stays a BC key —
 *       a SunEC {@code EdDSAPrivateKeyImpl} is rejected by BC's TLS crypto.</li>
 * </ul>
 *
 * <p>The context is a JDK-provider {@link JdkSslContext} wrapping the BCJSSE {@link SSLContext}, with
 * gRPC's mandatory ALPN ({@code h2}) configured explicitly.
 */
public final class ClprMtlsContexts {
    private static final List<String> SUPPORTED_CIPHERS = List.of(
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_AES_256_GCM_SHA384");

    private static final String[] SUPPORTED_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

    /** gRPC requires ALPN to negotiate HTTP/2. */
    private static final ApplicationProtocolConfig ALPN = new ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2);

    private static final String BCJSSE = "BCJSSE";

    /**
     * Cache of the leaf {@link KeyManager}s, keyed by the {@link ClprLeafCredentials} instance they were
     * built from. The key-manager chain (PKCS12 keystore + {@link KeyManagerFactory}) depends only on the
     * leaf, which is a per-process singleton generated once by {@code ClprLeafCertManager} and never
     * rotated; only the trust manager varies per call. Rebuilding it on every outbound sync was wasted
     * work, so it is memoized here. Keyed by identity ({@code !=}) rather than value: tests that mint
     * their own leaves simply recompute rather than collide on a record {@code equals} over key material.
     */
    private static volatile ClprLeafCredentials cachedLeaf;

    private static volatile KeyManager[] cachedKeyManagers;

    private ClprMtlsContexts() {}

    /**
     * Registers the BouncyCastle crypto and JSSE providers if absent. Idempotent and safe to call
     * repeatedly. Providers are <em>appended</em> (lowest precedence), so ordinary JVM TLS keeps using
     * the platform default; only explicit {@code BCJSSE}/{@code BC} lookups reach BouncyCastle.
     */
    public static synchronized void ensureProvidersRegistered() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (Security.getProvider(BCJSSE) == null) {
            Security.addProvider(new BouncyCastleJsseProvider());
        }
    }

    /**
     * Server context for the dedicated mTLS sync listener: presents this node's Ed25519 leaf, requires
     * a client certificate ({@link ClientAuth#REQUIRE}), and accepts the client's leaf only if it was
     * signed by one of the currently-known peer CAs.
     */
    @NonNull
    public static SslContext serverContext(
            @NonNull final ClprLeafCredentials leaf,
            @NonNull final Supplier<Map<X500Principal, List<X509Certificate>>> peerCasByIssuer)
            throws SSLException {
        requireNonNull(leaf);
        requireNonNull(peerCasByIssuer);
        return build(leaf, ClprMtlsTrust.peerSetClientTrustManager(peerCasByIssuer), false, ClientAuth.REQUIRE);
    }

    /**
     * Outbound client context: presents this node's Ed25519 leaf and pins {@code peerCaCert} as the sole
     * trust anchor for the peer server's leaf.
     */
    @NonNull
    public static SslContext clientContext(
            @NonNull final ClprLeafCredentials leaf, @NonNull final X509Certificate peerCaCert) throws SSLException {
        requireNonNull(leaf);
        requireNonNull(peerCaCert);
        return build(leaf, ClprMtlsTrust.pinnedServerTrustManager(peerCaCert), true, ClientAuth.NONE);
    }

    private static SslContext build(
            final ClprLeafCredentials leaf,
            final TrustManager trustManager,
            final boolean forClient,
            final ClientAuth clientAuth)
            throws SSLException {
        ensureProvidersRegistered();
        try {
            final var sslContext = SSLContext.getInstance("TLS", BCJSSE);
            sslContext.init(keyManagersFor(leaf), new TrustManager[] {trustManager}, new SecureRandom());

            return new JdkSslContext(
                    sslContext, forClient, SUPPORTED_CIPHERS, INSTANCE, ALPN, clientAuth, SUPPORTED_PROTOCOLS, false);
        } catch (final SSLException e) {
            throw e;
        } catch (final Exception e) {
            throw new SSLException("Failed to build CLPR mTLS SslContext on BCJSSE", e);
        }
    }

    /**
     * Returns the leaf {@link KeyManager}s for {@code leaf}, building them on first use and reusing them
     * for every subsequent call with the same leaf instance. {@code synchronized} so the check-and-build
     * is atomic across the concurrent sync threads; only the cheap identity check is serialized, while the
     * one-time keystore/{@link KeyManagerFactory} build runs at most once per leaf.
     */
    private static synchronized KeyManager[] keyManagersFor(final ClprLeafCredentials leaf) throws Exception {
        if (cachedLeaf != leaf) {
            cachedKeyManagers = buildKeyManagers(leaf);
            cachedLeaf = leaf;
        }
        return cachedKeyManagers;
    }

    /** Builds the BCJSSE key managers presenting {@code leaf} — a BC {@code PKCS12} keystore + PKIX KMF. */
    private static KeyManager[] buildKeyManagers(final ClprLeafCredentials leaf) throws Exception {
        final var keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME);
        keyStore.load(null, null);
        keyStore.setKeyEntry("clpr-leaf", leaf.privateKey(), new char[0], new Certificate[] {leaf.certificate()});
        final var kmf = KeyManagerFactory.getInstance("PKIX", BCJSSE);
        kmf.init(keyStore, new char[0]);
        return kmf.getKeyManagers();
    }
}
