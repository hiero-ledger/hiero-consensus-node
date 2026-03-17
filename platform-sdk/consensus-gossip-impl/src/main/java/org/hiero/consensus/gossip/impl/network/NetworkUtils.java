// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.hiero.base.crypto.KeystoreUtils;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.exceptions.PlatformConstructionException;
import org.hiero.consensus.exceptions.ThrowableUtilities;
import org.hiero.consensus.gossip.impl.gossip.shadowgraph.SyncTimeoutException;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.gossip.impl.network.connectivity.TlsFactory;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public final class NetworkUtils {
    private static final Logger logger = LogManager.getLogger(NetworkUtils.class);

    private NetworkUtils() {}

    /**
     * Close all the {@link Closeable} instances supplied, ignoring any exceptions
     *
     * @param closeables the instances to close
     */
    public static void close(final Closeable... closeables) {
        for (final Closeable closeable : closeables) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (final IOException | RuntimeException ignored) {
                // we try to close, but ignore any issues if we fail
            }
        }
    }

    /**
     * Called when an exception happens while executing something that uses a connection. This method will close the
     * connection supplied and log the exception with an appropriate marker.
     *
     * @param e          the exception that was thrown
     * @param connection the connection used when the exception was thrown
     * @param socketExceptionRateLimiter a rate limiter for reporting full stack traces for socket exceptions
     * @throws InterruptedException if the provided exception is an {@link InterruptedException}, it will be rethrown
     *                              once the connection is closed
     */
    public static void handleNetworkException(
            final Exception e, final Connection connection, final RateLimiter socketExceptionRateLimiter)
            throws InterruptedException {
        final String description;
        // always disconnect when an exception gets thrown
        if (connection != null) {
            connection.disconnect();
            description = connection.getDescription();
        } else {
            description = null;
        }
        if (e instanceof final InterruptedException ie) {
            // we must make sure that the network thread can be interrupted
            throw ie;
        }
        // we use a different marker depending on what the root cause is
        final Marker marker = NetworkUtils.determineExceptionMarker(e);
        if (SOCKET_EXCEPTIONS.getMarker().equals(marker)) {
            if (socketExceptionRateLimiter.requestAndTrigger()) {
                logger.warn(marker, "Connection broken: {}", description, e);
            } else {
                final String formattedException = NetworkUtils.formatException(e);
                logger.warn(marker, "Connection broken: {} {}", description, formattedException);
            }
        } else {
            logger.error(EXCEPTION.getMarker(), "Connection broken: {}", description, e);
        }
    }

    /**
     * Determines the log marker to use for a connection exception based on the nested exception types
     *
     * @param e the exception thrown during a network operations
     * @return the marker to use for logging
     */
    public static Marker determineExceptionMarker(final Exception e) {
        return ThrowableUtilities.isCausedByIOException(e)
                        || ThrowableUtilities.isRootCauseSuppliedType(e, SyncTimeoutException.class)
                        // All SSLExceptions regardless of nested root cause need to be classified as SOCKET_EXCEPTIONS.
                        // https://github.com/hashgraph/hedera-services/issues/7762
                        || ThrowableUtilities.hasAnyCauseSuppliedType(e, SSLException.class)
                ? SOCKET_EXCEPTIONS.getMarker()
                : EXCEPTION.getMarker();
    }

    /**
     * Returns a string containing the exception class name and the exception message.
     *
     * @param e The exception to format.
     * @return The string containing the exception class name and message.
     */
    public static String formatException(final Throwable e) {
        if (e == null) {
            return "";
        }
        return "Caused by exception: " + e.getClass().getSimpleName() + " Message: " + e.getMessage() + " "
                + formatException(e.getCause());
    }

    /**
     * Create a TLS-based {@link SocketFactory} using the provided keys and certificates.
     * NOTE: This method is a stepping stone to decoupling the networking from the platform.
     *
     * @param selfId        the ID of the node
     * @param peers         the list of peers
     * @param ownKeysAndCerts  the keys and certificates to use for the TLS connections
     * @param configuration the configuration of the network
     * @return the created {@link SocketFactory}
     */
    public static @NonNull SocketFactory createSocketFactory(
            @NonNull final NodeId selfId,
            @NonNull final List<PeerInfo> peers,
            @NonNull final KeysAndCerts ownKeysAndCerts,
            @NonNull final Configuration configuration) {
        Objects.requireNonNull(selfId);
        Objects.requireNonNull(peers);
        Objects.requireNonNull(ownKeysAndCerts);
        Objects.requireNonNull(configuration);

        try {
            final PrivateKey agrKey = ownKeysAndCerts.agrKeyPair().getPrivate();
            final Certificate agrCert = ownKeysAndCerts.agrCert();
            final char[] password =
                    KeystoreUtils.getConfiguredPassword(configuration).toCharArray();

            // the agrKeyStore should contain an entry with both agrKeyPair.getPrivate() and agrCert
            // PKCS12 uses file extension .p12 or .pfx
            final KeyStore agrKeyStore = ConsensusCryptoUtils.createEmptyTrustStore();
            agrKeyStore.setKeyEntry("key", agrKey, password, new Certificate[] {agrCert});

            // "PKIX" may be more interoperable than KeyManagerFactory.getDefaultAlgorithm or
            // TrustManagerFactory.getDefaultAlgorithm(), which was "SunX509" on one system tested
            final KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(CryptoConstants.KEY_MANAGER_FACTORY_TYPE);
            keyManagerFactory.init(agrKeyStore, password);
            final TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(CryptoConstants.TRUST_MANAGER_FACTORY_TYPE);

            return new TlsFactory(selfId, peers, configuration, keyManagerFactory, trustManagerFactory);
        } catch (final NoSuchAlgorithmException | UnrecoverableKeyException | KeyStoreException e) {
            throw new PlatformConstructionException("A problem occurred while creating the SocketFactory", e);
        }
    }
}
