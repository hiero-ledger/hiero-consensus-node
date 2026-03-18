// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.connectivity;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
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
import java.util.Collection;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
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
     * @param selfId the id of this node
     * @param configuration configuration for the platform
     */
    public TlsFactory(
            @NonNull final Certificate agrCert,
            @NonNull final PrivateKey agrKey,
            @NonNull final List<PeerInfo> peers,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration)
            throws NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException {
        this.selfId = requireNonNull(selfId);
        this.configuration = requireNonNull(configuration);
        this.keyManagerFactory = ConsensusCryptoUtils.createKeyManagerFactory(agrCert, agrKey, configuration);
        this.trustManagerFactory = TrustManagerFactory.getInstance(CryptoConstants.TRUST_MANAGER_FACTORY_TYPE);
        this.sslContext = SSLContext.getInstance(CryptoConstants.SSL_VERSION);
        this.nonDetRandom = ConsensusCryptoUtils.getNonDetRandom();

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
        requireNonNull(hostname);
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
