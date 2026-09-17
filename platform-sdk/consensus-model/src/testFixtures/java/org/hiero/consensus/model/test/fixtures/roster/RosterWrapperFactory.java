// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.roster;

import static java.util.stream.Collectors.toCollection;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterEntryWrapper;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.roster.test.fixtures.RandomRosterEntryBuilder;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.test.fixtures.WeightGenerator;

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
        final Roster pbjRoster = RosterFactory.randomRoster(random, size, weightGenerator);
        return RosterWrapper.of(pbjRoster);
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
        final Roster pbjRoster = RosterFactory.randomRoster(random, size);
        return RosterWrapper.of(pbjRoster);
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
        try {
            // first we create a random roster with random keys
            final Roster pbjRoster = RosterFactory.randomRoster(random, size, weightGenerator);
            final int n = pbjRoster.rosterEntries().size();

            // then we generate real keys for each node and new roster entries with the generated keys
            final List<RosterEntry> rosterEntries = new ArrayList<>(n);
            final Map<NodeId, KeysAndCerts> keysAndCertsMap = new HashMap<>(n);
            for (final RosterEntry entry : pbjRoster.rosterEntries()) {
                final NodeId nodeId = NodeId.of(entry.nodeId());
                final KeysAndCerts keysAndCerts = generateKeys(random, nodeId, schema);
                keysAndCertsMap.put(nodeId, keysAndCerts);
                final Bytes gossipCaCertificate =
                        Bytes.wrap(keysAndCerts.sigCert().getEncoded());
                final RosterEntry newEntry = entry.copyBuilder()
                        .gossipCaCertificate(gossipCaCertificate)
                        .build();
                rosterEntries.add(newEntry);
            }

            return new RosterWithKeys(createRosterWrapper(rosterEntries), Collections.unmodifiableMap(keysAndCertsMap));
        } catch (final CertificateEncodingException e) {
            throw new IllegalStateException("Failed to generate keys for roster", e);
        }
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

        final RosterEntry entry =
                RandomRosterEntryBuilder.create(random).withNodeId(nodeId.id()).build();
        entries.add(entry);

        return createRosterWrapper(entries);
    }
}
