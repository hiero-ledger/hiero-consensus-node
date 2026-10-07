// SPDX-License-Identifier: Apache-2.0
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
import java.util.Random;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;

/**
 * Factory for creating Roster instances.
 */
public class RosterFactory {

    private RosterFactory() {}

    /**
     * Create a random roster with the given size and pre-generated keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @return a {@link Roster} instance
     */
    @NonNull
    public static Roster randomRoster(@NonNull final Random random, final int size) {
        return randomRoster(random, size, WeightGenerators.GAUSSIAN);
    }

    /**
     * Create a random roster with the given size and weight generator with pre-generated keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @return a {@link Roster} instance
     */
    @NonNull
    public static Roster randomRoster(
            @NonNull final Random random, final int size, @NonNull final WeightGenerator weightGenerator) {
        final List<Long> weights = weightGenerator.getWeights(random.nextLong(), size);
        final List<RosterEntry> rosterEntries = new ArrayList<>(size);
        NodeId nextNodeId = NodeId.FIRST_NODE_ID;
        for (int index = 0; index < size; index++) {
            final NodeId nodeId = nextNodeId;
            // randomly advance between 1 and 3 steps
            nextNodeId = NodeId.of(nextNodeId.id() + random.nextInt(3) + 1);

            final RosterEntry entry = RandomRosterEntryBuilder.create(random)
                    .withNodeId(nodeId.id())
                    .withWeight(weights.get(index))
                    .build();
            rosterEntries.add(entry);
        }
        return new Roster(rosterEntries);
    }

    /**
     * Create a roster for the given signers
     *
     * @param signers the signers as a map from {@link NodeId} to {@link KeysAndCerts}
     * @return a {@link Roster} instance
     */
    @NonNull
    public static Roster rosterOf(@NonNull final Map<NodeId, KeysAndCerts> signers) {
        final List<RosterEntry> rosterEntries = signers.entrySet().stream()
                .map(entry -> createRosterEntry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(RosterEntry::nodeId))
                .toList();
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    @NonNull
    private static RosterEntry createRosterEntry(
            @NonNull final NodeId nodeId, @NonNull final KeysAndCerts keysAndCerts) {
        try {
            final long id = nodeId.id();
            final Bytes certificate = Bytes.wrap(keysAndCerts.sigCert().getEncoded());
            final ServiceEndpoint serviceEndpoint = ServiceEndpoint.newBuilder()
                    .domainName(String.format("node-%d", id))
                    .port(8082)
                    .build();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(500)
                    .gossipCaCertificate(certificate)
                    .gossipEndpoint(serviceEndpoint)
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Exception while creating roster entry", e);
        }
    }
}
