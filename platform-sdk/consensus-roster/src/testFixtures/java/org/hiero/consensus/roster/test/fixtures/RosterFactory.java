// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster.test.fixtures;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.main.model.NodeId;
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
        return buildRandomRoster(random, size, WeightGenerators.GAUSSIAN, null).getRoster();
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
        return buildRandomRoster(random, size, weightGenerator, null).getRoster();
    }

    /**
     * Create a random roster with the given size and weight generator, generating real keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @return a {@link RosterWithKeys} instance
     */
    @NonNull
    public static RosterWithKeys randomRosterWithKeys(
            @NonNull final Random random, final int size, @NonNull final WeightGenerator weightGenerator) {
        return buildRandomRoster(random, size, weightGenerator, SigningSchema.RSA);
    }

    /**
     * Create a random roster with the given size and weight generator, generating real keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @param schema the signing schema to use for generating keys
     * @return a {@link RosterWithKeys} instance
     */
    @NonNull
    public static RosterWithKeys randomRosterWithKeys(
            @NonNull final Random random,
            final int size,
            @NonNull final WeightGenerator weightGenerator,
            @NonNull final SigningSchema schema) {
        return buildRandomRoster(random, size, weightGenerator, schema);
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
    private static RosterWithKeys buildRandomRoster(
            @NonNull final Random random,
            final int size,
            @NonNull final WeightGenerator weightGenerator,
            @Nullable final SigningSchema signingSchema) {
        final Map<NodeId, KeysAndCerts> privateKeys = new HashMap<>();
        final List<Long> weights = weightGenerator.getWeights(random.nextLong(), size);
        final List<RosterEntry> rosterEntries = new ArrayList<>(size);
        NodeId nextNodeId = NodeId.FIRST_NODE_ID;
        for (int index = 0; index < size; index++) {
            final NodeId nodeId = nextNodeId;
            // randomly advance between 1 and 3 steps
            nextNodeId = NodeId.of(nextNodeId.id() + random.nextInt(3) + 1);

            final RandomRosterEntryBuilder entryBuilder = RandomRosterEntryBuilder.create(random)
                    .withNodeId(nodeId.id())
                    .withWeight(weights.get(index));
            if (signingSchema != null) {
                final KeysAndCerts keysAndCerts = generateKeys(random, nodeId, signingSchema);
                privateKeys.put(nodeId, keysAndCerts);
                entryBuilder.withSigCert(keysAndCerts.sigCert());
            }
            rosterEntries.add(entryBuilder.build());
        }
        final Roster roster = Roster.newBuilder().rosterEntries(rosterEntries).build();
        return new RosterWithKeys(roster, privateKeys);
    }

    @NonNull
    private static KeysAndCerts generateKeys(
            @NonNull final Random random, @NonNull final NodeId nodeId, @NonNull final SigningSchema signingSchema) {
        try {
            final byte[] masterKey = new byte[64];
            random.nextBytes(masterKey);

            return KeysAndCertsGenerator.generate(nodeId, signingSchema);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to generate keys for node " + nodeId, e);
        }
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
