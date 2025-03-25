// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.common.utility.throttle.RateLimiter;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.Utilities;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.gossip.shadowgraph.SyncTimeoutException;
import com.swirlds.platform.network.connectivity.SocketFactory;
import com.swirlds.platform.network.connectivity.TlsFactory;
import com.swirlds.platform.system.PlatformConstructionException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Closeable;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
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
        return Utilities.isCausedByIOException(e)
                        || Utilities.isRootCauseSuppliedType(e, SyncTimeoutException.class)
                        // All SSLExceptions regardless of nested root cause need to be classified as SOCKET_EXCEPTIONS.
                        // https://github.com/hashgraph/hedera-services/issues/7762
                        || Utilities.hasAnyCauseSuppliedType(e, SSLException.class)
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
            return new TlsFactory(
                    ownKeysAndCerts.agrCert(), ownKeysAndCerts.agrKeyPair().getPrivate(), peers, selfId, configuration);
        } catch (final NoSuchAlgorithmException
                | UnrecoverableKeyException
                | KeyStoreException
                | CertificateException
                | IOException e) {
            throw new PlatformConstructionException("A problem occurred while creating the SocketFactory", e);
        }
    }
}
