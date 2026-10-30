// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.roster;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.consensus.test.fixtures.crypto.PreGeneratedX509Certs;

/**
 * Factory for creating RosterWrapper instances.
 */
public class RosterWrapperFactory {

    private RosterWrapperFactory() {}

    /**
     * Create a random roster with the given size and weight generator with pre-generated keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper randomRoster(
            @NonNull final Random random, final int size, @NonNull final WeightGenerator weightGenerator) {
        final List<NodeId> nodeIds = createRandomNodeIds(random, size);
        final List<Long> weights = weightGenerator.getWeights(random.nextLong(), size);
        final List<RosterEntry> rosterEntries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            final RosterEntry rosterEntry = createRandomRosterEntry(random, nodeIds.get(index), weights.get(index));
            rosterEntries.add(rosterEntry);
        }
        return createRosterWrapper(rosterEntries);
    }

    /**
     * Create a random roster with the given size and pre-generated keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper randomRoster(@NonNull final Random random, final int size) {
        return randomRoster(random, size, WeightGenerators.GAUSSIAN);
    }

    /**
     * Create a random roster with the given size and weight generator, generating real keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWithKeys randomRosterWithKeys(
            @NonNull final Random random, final int size, @NonNull final WeightGenerator weightGenerator) {
        return randomRosterWithKeys(random, size, weightGenerator, SigningSchema.RSA);
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
        final List<NodeId> nodeIds = createRandomNodeIds(random, size);
        final List<Long> weights = weightGenerator.getWeights(random.nextLong(), size);
        final List<RosterEntry> rosterEntries = new ArrayList<>(size);
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = new HashMap<>(size);
        for (int index = 0; index < size; index++) {
            final NodeId nodeId = nodeIds.get(index);
            final KeysAndCerts keysAndCerts = generateKeys(random, nodeId, schema);
            keysAndCertsMap.put(nodeId, keysAndCerts);
            final RosterEntry rosterEntry =
                    createRandomRosterEntry(random, nodeId, weights.get(index), keysAndCerts.sigCert());
            rosterEntries.add(rosterEntry);
        }
        return new RosterWithKeys(createRosterWrapper(rosterEntries), Collections.unmodifiableMap(keysAndCertsMap));
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

    /**
     * Create a RosterWrapper from a list of RosterEntry instances.
     *
     * @param rosterEntries the list of RosterEntry instances
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper createRosterWrapper(@NonNull final List<RosterEntry> rosterEntries) {
        return RosterWrapper.of(new Roster(rosterEntries));
    }

    /**
     * Create a RosterWrapper from a list of RosterEntry instances.
     *
     * @param rosterEntries the list of RosterEntry instances
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper createRosterWrapper(@NonNull final RosterEntry... rosterEntries) {
        return RosterWrapper.of(new Roster(List.of(rosterEntries)));
    }

    /**
     * returns a new roster with the same RosterEntries, minus the RosterEntry matching the given NodeId.
     *
     * @param roster the roster to remove the entry from
     * @param nodeId the nodeId of the entry to remove
     * @return a new roster with the same RosterEntries, minus the RosterEntry matching the given NodeId
     */
    public static RosterWrapper dropRosterEntryFromRoster(
            @NonNull final RosterWrapper roster, @NonNull final NodeId nodeId) {
        final List<RosterEntry> entries = roster.rosterEntries().stream()
                .filter(entry -> !entry.nodeId().equals(nodeId))
                .map(RosterEntryWrapper::toPbj)
                .toList();
        return createRosterWrapper(entries);
    }

    /**
     * returns a new roster with the same RosterEntries, plus a new RosterEntry with the given NodeId.
     *
     * @param roster the roster to add the entry to
     * @param nodeId the nodeId of the entry to add
     * @param random the random number generator to use
     * @return a new roster with the same RosterEntries, plus a new RosterEntry with the given NodeId
     */
    public static RosterWrapper addRandomRosterEntryToRoster(
            @NonNull final RosterWrapper roster, @NonNull final NodeId nodeId, @NonNull final Random random) {
        final List<RosterEntry> entries =
                roster.rosterEntries().stream().map(RosterEntryWrapper::toPbj).collect(toCollection(ArrayList::new));
        final long weight =
                WeightGenerators.GAUSSIAN.getWeights(random.nextLong(), 1).getFirst();
        final RosterEntry entry = createRandomRosterEntry(random, nodeId, weight);
        entries.add(entry);

        return createRosterWrapper(entries);
    }

    /**
     * returns a new roster with the same RosterEntries, but with the weight of the RosterEntry matching the given NodeId set to 0.
     *
     * @param roster the roster to modify
     * @param nodeId the nodeId of the entry to modify
     * @return a new roster with the same RosterEntries, but with the weight of the RosterEntry matching the given NodeId set to 0
     */
    public static RosterWrapper zeroOutWeightOfRosterEntry(
            @NonNull final RosterWrapper roster, @NonNull final NodeId nodeId) {
        final List<RosterEntry> entries = roster.rosterEntries().stream()
                .map(entry -> {
                    if (entry.nodeId().equals(nodeId)) {
                        return entry.toPbj().copyBuilder().weight(0L).build();
                    } else {
                        return entry.toPbj();
                    }
                })
                .toList();
        return createRosterWrapper(entries);
    }

    /**
     * Create a random RosterEntry with the given NodeId, weight, and certificate.
     *
     * @param random the source of randomness
     * @param nodeId the NodeId of the RosterEntry
     * @param weight the weight of the RosterEntry
     * @return a {@link RosterEntry} instance
     */
    @NonNull
    public static RosterEntry createRandomRosterEntry(
            @NonNull final Random random, @NonNull final NodeId nodeId, final long weight) {
        final X509Certificate certificate = requireNonNull(PreGeneratedX509Certs.getSigCert(nodeId.id()));
        return createRandomRosterEntry(random, nodeId, weight, certificate);
    }

    /**
     * Create a random RosterEntry with the given NodeId, weight, and certificate.
     *
     * @param random the source of randomness
     * @param nodeId the NodeId of the RosterEntry
     * @param weight the weight of the RosterEntry
     * @param certificate the certificate of the RosterEntry
     * @return a {@link RosterEntry} instance
     */
    @NonNull
    private static RosterEntry createRandomRosterEntry(
            @NonNull final Random random,
            @NonNull final NodeId nodeId,
            final long weight,
            @NonNull final X509Certificate certificate) {
        try {
            final Bytes sigCertBytes = Bytes.wrap(certificate.getEncoded());
            final String ip = RandomUtils.randomIp(random);
            final ServiceEndpoint serviceEndpoint = ServiceEndpoint.newBuilder()
                    .domainName(ip)
                    .port(random.nextInt(1, 65535))
                    .build();
            return RosterEntry.newBuilder()
                    .nodeId(nodeId.id())
                    .weight(weight)
                    .gossipCaCertificate(sigCertBytes)
                    .gossipEndpoint(serviceEndpoint)
                    .build();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Create a list of random NodeIds, starting with NodeId.FIRST_NODE_ID and incrementing by 1 to 3 for each subsequent NodeId.
     *
     * @param random the source of randomness
     * @param size the number of NodeIds to create
     * @return a list of random NodeIds
     */
    @NonNull
    private static List<NodeId> createRandomNodeIds(@NonNull final Random random, final int size) {
        final List<NodeId> nodeIds = new ArrayList<>(size);
        nodeIds.add(NodeId.FIRST_NODE_ID);
        for (int i = 1; i < size; i++) {
            final NodeId lastNodeId = nodeIds.get(i - 1);
            // randomly advance between 1 and 3 steps
            final NodeId nextNodeId = NodeId.of(lastNodeId.id() + random.nextInt(3) + 1);
            nodeIds.add(nextNodeId);
        }
        return nodeIds;
    }
}
