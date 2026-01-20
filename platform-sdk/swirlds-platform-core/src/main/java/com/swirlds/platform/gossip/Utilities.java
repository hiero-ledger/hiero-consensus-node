// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * General purpose utilities related to the gossip protocol and peer information.
 */
public class Utilities {

    private Utilities() {}

    /**
     * Create a list of PeerInfos from the roster. The list will contain information about all peers but not us.
     * Peers without valid gossip certificates are not included.
     *
     * @param roster
     * 		the roster to create the list from
     * @param selfId
     * 		our ID
     * @return a list of PeerInfo
     */
    public static @NonNull List<PeerInfo> createPeerInfoList(
            @NonNull final Roster roster, @NonNull final NodeId selfId) {
        Objects.requireNonNull(roster);
        Objects.requireNonNull(selfId);
        return roster.rosterEntries().stream()
                .filter(entry -> entry.nodeId() != selfId.id())
                // Only include peers with valid gossip certificates
                // https://github.com/hashgraph/hedera-services/issues/16648
                .filter(entry -> CryptoUtils.checkCertificate((RosterUtils.fetchGossipCaCertificate(entry))))
                .map(Utilities::toPeerInfo)
                .toList();
    }

    /**
     * Converts single roster entry to PeerInfo, which is more abstract class representing information about possible node connection
     * @param entry data to convert
     * @return PeerInfo with extracted hostname, port and certificate for remote host
     */
    public static @NonNull PeerInfo toPeerInfo(@NonNull RosterEntry entry) {
        Objects.requireNonNull(entry);
        return new PeerInfo(
                NodeId.of(entry.nodeId()),
                // Assume that the first ServiceEndpoint describes the external hostname,
                // which is the same order in which RosterRetriever.buildRoster(AddressBook) lists them.
                Objects.requireNonNull(RosterUtils.fetchHostname(entry, 0)),
                RosterUtils.fetchPort(entry, 0),
                Objects.requireNonNull(RosterUtils.fetchGossipCaCertificate(entry)));
    }
}
