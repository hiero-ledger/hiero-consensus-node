// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.security.auth.x500.X500Principal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Identifies a connected peer from a list of trusted peers; it is used only to handle incoming connections
 */
public class NetworkPeerIdentifier {

    private static final Logger logger = LogManager.getLogger(NetworkPeerIdentifier.class);

    /**
     * limits the frequency of error log statements
     */
    private final RateLimitedLogger noPeerFoundLogger;

    // a mapping of X500Principal and their peers
    private final Map<X500Principal, PeerInfo> x501PrincipalsAndPeers;

    /**
     * constructor
     *
     * @param platformContext the platform context
     * @param peers           list of peers
     */
    public NetworkPeerIdentifier(@NonNull final PlatformContext platformContext, @NonNull final List<PeerInfo> peers) {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(peers);
        noPeerFoundLogger = new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(5));

        this.x501PrincipalsAndPeers = peers.stream()
                .collect(Collectors.toMap(
                        peer -> peer.signingCertificate().getSubjectX500Principal(), Function.identity()));
    }

    /**
     * Identifies a client on the other end of the socket using their signing certificate.
     *
     * @param certs a list of TLS certificates from the connected socket
     * @return info of the identified peer
     */
    public @Nullable PeerInfo identifyTlsPeer(@NonNull final Certificate[] certs) {
        Objects.requireNonNull(certs);
        if (certs.length == 0) {
            return null;
        }

        // the peer certificates chain is an ordered array of peer certificates,
        // with the peer's own certificate first followed by any certificate authorities.
        // See https://www.rfc-editor.org/rfc/rfc5246
        final X509Certificate agreementCert = (X509Certificate) certs[0];
        final PeerInfo matchedPeer = x501PrincipalsAndPeers.get(agreementCert.getIssuerX500Principal());
        if (matchedPeer == null) {
            noPeerFoundLogger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    "Unable to identify peer with the presented certificate {}.",
                    agreementCert);
        }
        return matchedPeer;
    }
}
