// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.connectivity;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.exceptions.PlatformConstructionException;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.gossip.Utilities;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
import org.hiero.consensus.model.node.NodeId;

/**
 * used to create and receive TLS connections, based on the given trustStore
 */
public class TlsFactory implements SocketFactory {

    private static final Logger logger = LogManager.getLogger(TlsFactory.class);
    private static final AtomicBoolean handshakeDiagLogged = new AtomicBoolean(false);

    private SSLServerSocketFactory sslServerSocketFactory;
    private SSLSocketFactory sslSocketFactory;

    private final Configuration configuration;
    private final NodeId selfId;
    private final SSLContext sslContext;
    private final SecureRandom nonDetRandom;
    private final KeyManagerFactory keyManagerFactory;
    private final TrustManagerFactory trustManagerFactory;

    /**
     * Construct this object to create and receive TLS connections.
     * @param agrCert the TLS certificate to use
     * @param sigCert the CA certificate that signed agrCert (for chain validation)
     * @param agrKey the private key corresponding to the public key in the certificate
     * @param peers the list of peers to allow connections with
     * @param selfId the id of this node
     * @param configuration configuration for the platform
     */
    public TlsFactory(
            @NonNull final Certificate agrCert,
            @NonNull final Certificate sigCert,
            @NonNull final PrivateKey agrKey,
            @NonNull final List<PeerInfo> peers,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration)
            throws NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException {
        this.selfId = requireNonNull(selfId);
        this.configuration = requireNonNull(configuration);
        try {
            this.keyManagerFactory =
                    ConsensusCryptoUtils.createKeyManagerFactory(agrCert, sigCert, agrKey, configuration);
            this.trustManagerFactory = TrustManagerFactory.getInstance(
                    CryptoConstants.TRUST_MANAGER_FACTORY_TYPE, CryptoConstants.TRUST_MANAGER_FACTORY_PROVIDER);
            this.sslContext = SSLContext.getInstance(CryptoConstants.SSL_VERSION, CryptoConstants.SSL_PROVIDER);
        } catch (NoSuchProviderException e) {
            throw new PlatformConstructionException("BCJSSE provider not available", e);
        }
        this.nonDetRandom = ConsensusCryptoUtils.getNonDetRandom();

        reload(peers);
        logProviderDiagnostics();
    }

    private void logProviderDiagnostics() {
        final String expectedProvider = CryptoConstants.SSL_PROVIDER;
        final String expectedProtocol = CryptoConstants.SSL_VERSION;
        final String actualProvider = sslContext.getProvider().getName();
        final String actualProtocol = sslContext.getProtocol();
        final String kmfProvider = keyManagerFactory.getProvider().getName();
        final String tmfProvider = trustManagerFactory.getProvider().getName();
        final String[] supportedProtocols = sslContext.getSupportedSSLParameters().getProtocols();
        final boolean providerOk = expectedProvider.equals(actualProvider);
        final boolean protocolOk = expectedProtocol.equals(actualProtocol);

        logger.info(
                LogMarker.STARTUP.getMarker(),
                "TLS setup: SSLContext provider={} (expected={}), protocol={} (expected={}), KMF provider={}, TMF provider={}, supportedProtocols={}, cipher={}, namedGroups={}, signatureSchemes={}",
                actualProvider,
                expectedProvider,
                actualProtocol,
                expectedProtocol,
                kmfProvider,
                tmfProvider,
                Arrays.toString(supportedProtocols),
                CryptoConstants.TLS_SUITE,
                Arrays.toString(CryptoConstants.TLS_NAMED_GROUPS),
                Arrays.toString(CryptoConstants.TLS_SIGNATURE_SCHEMES));

        if (!providerOk || !protocolOk) {
            logger.error(
                    LogMarker.ERROR.getMarker(),
                    "TLS setup mismatch: provider {} vs expected {}, protocol {} vs expected {}",
                    actualProvider,
                    expectedProvider,
                    actualProtocol,
                    expectedProtocol);
        }

        final StringBuilder providers = new StringBuilder();
        for (final Provider p : Security.getProviders()) {
            if (providers.length() > 0) {
                providers.append(", ");
            }
            providers.append(p.getName()).append(' ').append(p.getVersionStr());
        }
        logger.info(LogMarker.STARTUP.getMarker(), "Registered JCA providers (in order): {}", providers);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ServerSocket createServerSocket(final int port) throws IOException {
        final SSLServerSocket serverSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
        serverSocket.setEnabledCipherSuites(new String[] {CryptoConstants.TLS_SUITE});
        serverSocket.setWantClientAuth(true);
        serverSocket.setNeedClientAuth(true);
        final SSLParameters params = serverSocket.getSSLParameters();
        params.setNamedGroups(CryptoConstants.TLS_NAMED_GROUPS);
        params.setSignatureSchemes(CryptoConstants.TLS_SIGNATURE_SCHEMES);
        serverSocket.setSSLParameters(params);
        final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
        final GossipConfig gossipConfig = configuration.getConfigData(GossipConfig.class);
        SocketFactory.configureAndBind(selfId, serverSocket, socketConfig, gossipConfig, port);
        return serverSocket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Socket createClientSocket(@NonNull final String hostname, final int port) throws IOException {
        requireNonNull(hostname);
        synchronized (this) {
            final SSLSocket clientSocket = (SSLSocket) sslSocketFactory.createSocket();
            // ensure the connection is ALWAYS the exact cipher suite we've chosen
            clientSocket.setEnabledCipherSuites(new String[] {CryptoConstants.TLS_SUITE});
            clientSocket.setWantClientAuth(true);
            clientSocket.setNeedClientAuth(true);
            final SSLParameters params = clientSocket.getSSLParameters();
            params.setNamedGroups(CryptoConstants.TLS_NAMED_GROUPS);
            params.setSignatureSchemes(CryptoConstants.TLS_SIGNATURE_SCHEMES);
            clientSocket.setSSLParameters(params);
            final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
            SocketFactory.configureAndConnect(clientSocket, socketConfig, hostname, port);
            clientSocket.startHandshake();
            logHandshakeOnce(clientSocket);
            return clientSocket;
        }
    }

    private static void logHandshakeOnce(@NonNull final SSLSocket socket) {
        if (!handshakeDiagLogged.compareAndSet(false, true)) {
            return;
        }
        final SSLSession session = socket.getSession();
        final String protocol = session.getProtocol();
        final String cipher = session.getCipherSuite();
        final boolean tls13 = CryptoConstants.SSL_VERSION.equals(protocol);
        final boolean expectedCipher = CryptoConstants.TLS_SUITE.equals(cipher);
        if (tls13 && expectedCipher) {
            logger.info(
                    LogMarker.STARTUP.getMarker(),
                    "TLS handshake OK: negotiated protocol={}, cipher={}, peer={}",
                    protocol,
                    cipher,
                    socket.getInetAddress());
        } else {
            logger.error(
                    LogMarker.ERROR.getMarker(),
                    "TLS handshake mismatch: negotiated protocol={} (expected {}), cipher={} (expected {})",
                    protocol,
                    CryptoConstants.SSL_VERSION,
                    cipher,
                    CryptoConstants.TLS_SUITE);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reload(@NonNull final Collection<PeerInfo> peers) {
        try {
            synchronized (this) {
                // we just reset the list for now, until the work to calculate diffs is done
                // then, we will have two lists of peers to add and to remove
                final KeyStore signingTrustStore = Utilities.createPublicKeyStore(requireNonNull(peers));
                trustManagerFactory.init(signingTrustStore);
                sslContext.init(
                        keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), nonDetRandom);
                sslServerSocketFactory = sslContext.getServerSocketFactory();
                sslSocketFactory = sslContext.getSocketFactory();
            }
        } catch (final KeyStoreException | KeyManagementException e) {
            throw new PlatformConstructionException("A problem occurred while initializing the SocketFactory", e);
        }
    }
}