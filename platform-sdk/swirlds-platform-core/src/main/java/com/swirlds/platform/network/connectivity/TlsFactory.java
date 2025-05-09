// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.connectivity;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.gossip.config.GossipConfig;
import com.swirlds.platform.network.PeerInfo;
import com.swirlds.platform.network.SocketConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.exceptions.PlatformConstructionException;
import org.hiero.consensus.model.node.NodeId;

/**
 * used to create and receive TLS connections, based on the given trustStore
 */
public class TlsFactory implements SocketFactory {
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
     * @param agrKey the private key corresponding to the public key in the certificate
     * @param peers the list of peers to allow connections with
     * @param configuration configuration for the platform
     */
    public TlsFactory(
            @NonNull final Certificate agrCert,
            @NonNull final PrivateKey agrKey,
            @NonNull final List<PeerInfo> peers,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration)
            throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException,
                    UnrecoverableKeyException {
        Objects.requireNonNull(agrCert);
        Objects.requireNonNull(agrKey);
        Objects.requireNonNull(peers);
        this.selfId = Objects.requireNonNull(selfId);
        this.configuration = Objects.requireNonNull(configuration);
        final CryptoConfig configData = configuration.getConfigData(CryptoConfig.class);
        final char[] password = configData.keystorePassword().toCharArray();

        /* nondeterministic CSPRNG */
        this.nonDetRandom = CryptoStatic.getNonDetRandom();

        // the agrKeyStore should contain an entry with both agrKeyPair.getPrivate() and agrCert
        // PKCS12 uses file extension .p12 or .pfx
        final KeyStore agrKeyStore = KeyStore.getInstance(CryptoConstants.KEYSTORE_TYPE);
        agrKeyStore.load(null, null); // initialize
        agrKeyStore.setKeyEntry("key", agrKey, password, new Certificate[] {agrCert});

        // "PKIX" may be more interoperable than KeyManagerFactory.getDefaultAlgorithm or
        // TrustManagerFactory.getDefaultAlgorithm(), which was "SunX509" on one system tested
        this.keyManagerFactory = KeyManagerFactory.getInstance(CryptoConstants.KEY_MANAGER_FACTORY_TYPE);
        keyManagerFactory.init(agrKeyStore, password);
        this.trustManagerFactory = TrustManagerFactory.getInstance(CryptoConstants.TRUST_MANAGER_FACTORY_TYPE);
        this.sslContext = SSLContext.getInstance(CryptoConstants.SSL_VERSION);

        reload(peers);
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
        Objects.requireNonNull(hostname);
        synchronized (this) {
            final SSLSocket clientSocket = (SSLSocket) sslSocketFactory.createSocket();
            // ensure the connection is ALWAYS the exact cipher suite we've chosen
            clientSocket.setEnabledCipherSuites(new String[] {CryptoConstants.TLS_SUITE});
            clientSocket.setWantClientAuth(true);
            clientSocket.setNeedClientAuth(true);
            final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
            SocketFactory.configureAndConnect(clientSocket, socketConfig, hostname, port);
            clientSocket.startHandshake();
            return clientSocket;
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
                final KeyStore signingTrustStore = CryptoStatic.createPublicKeyStore(Objects.requireNonNull(peers));
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
