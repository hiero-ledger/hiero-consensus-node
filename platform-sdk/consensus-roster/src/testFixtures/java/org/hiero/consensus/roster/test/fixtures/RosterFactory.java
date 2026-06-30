package org.hiero.consensus.roster.test.fixtures;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * Factory for creating Roster instances.
 */
public class RosterFactory {

    private RosterFactory() {}

    /**
     * Create a Roster for the given signers
     */
    @NonNull
    public static Roster generateRoster(@NonNull final Map<NodeId, KeysAndCerts> signers) {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        for (final Entry<NodeId, KeysAndCerts> signer : signers.entrySet()) {
            rosterEntries.add(createRosterEntry(signer.getKey(), signer.getValue()));
        }
        rosterEntries.sort(Comparator.comparingLong(RosterEntry::nodeId));
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    @NonNull
    private static RosterEntry createRosterEntry(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        try {
            final long id = nodeId.id();
            final byte[] certificate = keysAndCerts.sigCert().getEncoded();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(500)
                    .gossipCaCertificate(Bytes.wrap(certificate))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format("node-%d", id))
                            .port(8082)
                            .build())
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Exception while creating roster entry", e);
        }
    }
}
