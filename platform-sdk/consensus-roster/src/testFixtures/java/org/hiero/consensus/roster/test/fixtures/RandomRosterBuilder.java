// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster.test.fixtures;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.WeightGenerator;
import org.hiero.consensus.test.fixtures.WeightGenerators;

/**
 * A utility for generating a random roster.
 */
public class RandomRosterBuilder {

    /**
     * All randomness comes from this.
     */
    private final Random random;

    /**
     * The number of roster entries to put into the roster.
     */
    private int size = 4;

    private WeightGenerator weightGenerator = WeightGenerators.GAUSSIAN;

    /**
     * The minimum weight to give to any particular address.
     */
    private long minimumWeight = 0;

    /**
     * The maximum weight to give to any particular address.
     */
    private Long maximumWeight;

    /**
     * the next available node id for new addresses.
     */
    private NodeId nextNodeId = NodeId.FIRST_NODE_ID;

    /**
     * If true then generate real cryptographic keys.
     */
    private boolean realKeys;

    /**
     * If we are using real keys, this map will hold the private keys for each address.
     */
    private final Map<NodeId, KeysAndCerts> privateKeys = new HashMap<>();

    /**
     * Create a new random roster generator.
     *
     * @param random a source of randomness
     * @return a new random roster generator
     */
    @NonNull
    public static RandomRosterBuilder create(@NonNull final Random random) {
        return new RandomRosterBuilder(random);
    }

    /**
     * Constructor.
     *
     * @param random a source of randomness
     */
    private RandomRosterBuilder(@NonNull final Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Build a random roster given the provided configuration.
     */
    @NonNull
    public Roster build() {
        final Roster.Builder builder = Roster.newBuilder();

        if (maximumWeight == null && size > 0) {
            // We don't want the total weight to overflow a long
            maximumWeight = Long.MAX_VALUE / size;
        }

        final List<Long> weights = weightGenerator.getWeights(random.nextLong(), size).stream()
                .map(this::applyWeightBounds)
                .toList();
        builder.rosterEntries(IntStream.range(0, size)
                .mapToObj(index -> {
                    final NodeId nodeId = getNextNodeId();
                    final RandomRosterEntryBuilder addressBuilder = RandomRosterEntryBuilder.create(random)
                            .withNodeId(nodeId.id())
                            .withWeight(weights.get(index));
                    generateKeys(nodeId, addressBuilder);
                    return addressBuilder.build();
                })
                .toList());

        return builder.build();
    }

    /**
     * Builds the roster and returns it together with the cryptographic keys for each node. Requires
     * {@link #withRealKeysEnabled(boolean)} to have been set to {@code true}.
     *
     * @return the roster bundled with its cryptographic keys
     * @throws IllegalStateException if real keys are not enabled
     */
    public RosterWithKeys buildWithKeys() {
        if (!realKeys) {
            throw new IllegalStateException("Cannot build roster with keys when real keys are not enabled");
        }
        final Roster roster = build();
        return new RosterWithKeys(roster, privateKeys);
    }

    /**
     * Set the size of the roster.
     *
     * @return this object
     */
    @NonNull
    public RandomRosterBuilder withSize(final int size) {
        this.size = size;
        return this;
    }

    /**
     * Set the weight generator for the roster.
     *
     * @return this object
     */
    @NonNull
    public RandomRosterBuilder withWeightGenerator(@NonNull final WeightGenerator weightGenerator) {
        this.weightGenerator = weightGenerator;
        return this;
    }

    /**
     * Set the minimum weight for an address. Overrides the weight generation strategy.
     *
     * @return this object
     */
    @NonNull
    public RandomRosterBuilder withMinimumWeight(final long minimumWeight) {
        this.minimumWeight = minimumWeight;
        return this;
    }

    /**
     * Set the maximum weight for an address. Overrides the weight generation strategy.
     *
     * @return this object
     */
    @NonNull
    public RandomRosterBuilder withMaximumWeight(final long maximumWeight) {
        this.maximumWeight = maximumWeight;
        return this;
    }

    /**
     * Specify if real cryptographic keys should be generated (default false). Warning: generating real keys is very
     * time consuming.
     *
     * @param realKeysEnabled if true then generate real cryptographic keys
     * @return this object
     */
    @NonNull
    public RandomRosterBuilder withRealKeysEnabled(final boolean realKeysEnabled) {
        this.realKeys = realKeysEnabled;
        return this;
    }

    /**
     * Generate the next node ID.
     */
    @NonNull
    private NodeId getNextNodeId() {
        final NodeId nextId = nextNodeId;
        // randomly advance between 1 and 3 steps
        final int randomAdvance = random.nextInt(3);
        nextNodeId = nextNodeId.getOffset(randomAdvance + 1L);
        return nextId;
    }

    private long applyWeightBounds(final long weight) {
        return Math.min(maximumWeight, Math.max(minimumWeight, weight));
    }

    /**
     * Generate the cryptographic keys for a node.
     */
    private void generateKeys(@NonNull final NodeId nodeId, @NonNull final RandomRosterEntryBuilder addressBuilder) {
        if (realKeys) {
            try {
                final byte[] masterKey = new byte[64];
                random.nextBytes(masterKey);

                final KeysAndCerts keysAndCerts = KeysAndCertsGenerator.generate(nodeId);
                privateKeys.put(nodeId, keysAndCerts);

                addressBuilder.withSigCert(keysAndCerts.sigCert());

            } catch (final Exception e) {
                throw new RuntimeException();
            }
        }
    }
}
