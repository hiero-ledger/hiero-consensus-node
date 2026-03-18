// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import static org.hiero.consensus.crypto.KeyCertPurpose.SIGNING;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.crypto.CryptoConstants;
import org.hiero.consensus.gossip.impl.network.PeerInfo;
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
    @NonNull
    public static List<PeerInfo> createPeerInfoList(@NonNull final Roster roster, @NonNull final NodeId selfId) {
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
    @NonNull
    public static PeerInfo toPeerInfo(@NonNull final RosterEntry entry) {
        Objects.requireNonNull(entry);
        return new PeerInfo(
                NodeId.of(entry.nodeId()),
                // Assume that the first ServiceEndpoint describes the external hostname,
                // which is the same order in which RosterRetriever.buildRoster(AddressBook) lists them.
                Objects.requireNonNull(RosterUtils.fetchHostname(entry, 0)),
                RosterUtils.fetchPort(entry, 0),
                Objects.requireNonNull(RosterUtils.fetchGossipCaCertificate(entry)));
    }

    /**
     * Create a trust store that contains the public keys of all the members in the peer list
     *
     * @param peers all the peers in the network
     * @return a trust store containing the public keys of all the members
     * @throws KeyStoreException if there is no provider that supports {@link CryptoConstants#KEYSTORE_TYPE}
     */
    public static @NonNull KeyStore createPublicKeyStore(@NonNull final Collection<PeerInfo> peers)
            throws KeyStoreException {
        Objects.requireNonNull(peers);
        final KeyStore store = ConsensusCryptoUtils.createEmptyTrustStore();
        for (final PeerInfo peer : peers) {
            final Certificate sigCert = peer.signingCertificate();
            store.setCertificateEntry(SIGNING.storeName(peer.nodeId()), sigCert);
        }
        return store;
    }
}
