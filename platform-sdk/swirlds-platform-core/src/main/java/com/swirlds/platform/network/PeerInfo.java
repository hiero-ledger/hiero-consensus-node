// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.swirlds.platform.roster.RosterEntryNotFoundException;
import com.swirlds.platform.roster.RosterUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;
import java.util.Collection;
import org.hiero.consensus.model.node.NodeId;

/**
 * A record representing a peer's network information.  If the certificate is not null, it must be encodable as a valid
 * X509Certificate.  It is assumed the calling code has already validated the certificate.
 *
 * @param nodeId             the ID of the peer
 * @param hostname           the hostname (or IP address) of the peer
 * @param port               the port on which peer is listening for incoming connections
 * @param signingCertificate the certificate used to validate the peer's TLS certificate
 */
public record PeerInfo(
        @NonNull NodeId nodeId, @NonNull String hostname, int port, @NonNull X509Certificate signingCertificate) {

    /**
     * Return a "node name" for the peer, e.g. "node1" for a peer with NodeId == 0.
     *
     * @return a "node name"
     */
    @NonNull
    public String nodeName() {
        return RosterUtils.formatNodeName(nodeId.id());
    }

    public static @NonNull PeerInfo find(@NonNull Collection<PeerInfo> peers, @NonNull NodeId nodeId) {
        for (PeerInfo peer : peers) {
            if (peer.nodeId().equals(nodeId)) {
                return peer;
            }
        }
        throw new RosterEntryNotFoundException("No RosterEntry with nodeId: " + nodeId + " in peer list: "
                + peers.stream().map(it -> it.nodeId).toList());
    }
}
