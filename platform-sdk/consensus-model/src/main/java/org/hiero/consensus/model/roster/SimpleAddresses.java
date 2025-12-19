// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public record SimpleAddresses(List<SimpleAddress> addresses) {
    public Set<NodeId> getNodeIds() {
        return addresses.stream().map(a -> NodeId.of(a.nodeId())).collect(Collectors.toSet());
    }

    @Nullable
    public SimpleAddress get(final long nodeId) {
        return addresses.stream().filter(a -> a.nodeId() == nodeId).findFirst().orElse(null);
    }

    public SimpleAddresses withKeysAndCerts(final Map<NodeId, KeysAndCerts> keysAndCerts) {
        final List<SimpleAddress> newAddresses = new ArrayList<>();
        addresses.forEach(address -> {
            final KeysAndCerts certsCandidate = keysAndCerts.get(NodeId.of(address.nodeId()));
            final SimpleAddress addressCandidate = get(address.nodeId());
            if (addressCandidate != null && certsCandidate != null) {
                newAddresses.add(addressCandidate.withKeysAndCerts(certsCandidate));
            }
        });

        return new SimpleAddresses(newAddresses);
    }

    public Roster asRoster() {
        final var builder = Roster.newBuilder();

        builder.rosterEntries(addresses().stream()
                .map(a -> {
                    try {
                        final Bytes cert =
                                a.keysAndCerts() != null ? Bytes.wrap(a.keysAndCerts().agrCert().getEncoded()) : Bytes.EMPTY;
                        return RosterEntry.newBuilder()
                                .nodeId(a.nodeId())
                                .weight(a.weight())
                                .gossipEndpoint(a.serviceEndpoints())
                                .gossipCaCertificate(cert)
                                .build();
                    } catch (CertificateEncodingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList());

        return builder.build();
    }
}
