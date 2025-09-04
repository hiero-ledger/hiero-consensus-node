// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.swirlds.platform.crypto.KeyCertPurpose.SIGNING;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.crypto.KeyLoadingException;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.SerializableX509Certificate;

public class RosterFactory {

    private final Roster roster;
    private final Map<NodeId, KeysAndCerts> keysAndCerts;

    public RosterFactory(final Randotron randotron, final int count, final WeightGenerator weightGenerator) {
        try {
            final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(randotron)
                    .withSize(count)
                    .withWeightGenerator(weightGenerator)
                    .withRealKeysEnabled(false);
            final Roster preliminaryRoster = rosterBuilder.build();

            final List<NodeId> sortedNodeIds = preliminaryRoster.rosterEntries().stream()
                    .map(RosterEntry::nodeId)
                    .map(NodeId::of)
                    .sorted()
                    .toList();

            final PublicStores publicStores = new PublicStores();
            keysAndCerts = CryptoStatic.generateKeysAndCerts(sortedNodeIds, publicStores);

            roster = preliminaryRoster
                    .copyBuilder()
                    .rosterEntries(preliminaryRoster.rosterEntries().stream()
                            .map(rosterEntry -> addCertificateToEntry(rosterEntry, publicStores))
                            .toList())
                    .build();

        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException("Failed to generate keys and certificates for nodes", e);
        }
    }

    public Roster roster() {
        return roster;
    }

    public KeysAndCerts keyAndCerts(@NonNull long nodeId) {
        return keysAndCerts.get(NodeId.of(nodeId));
    }

    private RosterEntry addCertificateToEntry(final RosterEntry entry, final PublicStores publicStores) {
        try {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final SerializableX509Certificate certificate =
                    new SerializableX509Certificate(publicStores.getCertificate(SIGNING, nodeId));
            final Bytes serializedCertificate =
                    Bytes.wrap(certificate.getCertificate().getEncoded());
            return entry.copyBuilder()
                    .gossipCaCertificate(serializedCertificate)
                    .build();
        } catch (final KeyLoadingException | CertificateEncodingException e) {
            throw new RuntimeException("Failed to add certificate to roster entry for node " + entry.nodeId(), e);
        }
    }
}
