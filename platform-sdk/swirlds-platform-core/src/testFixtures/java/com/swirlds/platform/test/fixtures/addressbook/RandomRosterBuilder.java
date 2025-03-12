// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import static com.swirlds.platform.crypto.KeyCertPurpose.SIGNING;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.GaussianWeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerator;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.crypto.SerializableX509Certificate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.IntStream;

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

    private WeightGenerator weightGenerator = new GaussianWeightGenerator(1000, 100);

    /**
     * Describes different ways that the random roster has its weight distributed if the custom strategy lambda is
     * unset.
     */
    public enum WeightDistributionStrategy {
        /**
         * All nodes have equal weight.
         */
        BALANCED,
        /**
         * Nodes are given weight with a gaussian distribution.
         */
        GAUSSIAN
    }

    /**
     * The weight distribution strategy.
     */
    private WeightDistributionStrategy weightDistributionStrategy = WeightDistributionStrategy.GAUSSIAN;

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

    private void checkConstraints() {
        if (weightDistributionStrategy != null && weightGenerator != null) {
            throw new IllegalStateException("Weight generator and weight distribution strategy cannot be both set");
        }
    }

    /**
     * Build a random roster given the provided configuration.
     */
    @NonNull
    public Roster build() {
        checkConstraints();
        final Roster.Builder builder = Roster.newBuilder();

        if (maximumWeight == null && size > 0) {
            // We don't want the total weight to overflow a long
            maximumWeight = Long.MAX_VALUE / size;
        }
        if (weightGenerator == null) {
            weightGenerator = switch (weightDistributionStrategy) {
                case BALANCED -> (l, i) -> WeightGenerators.balancedNodeWeights(size, size * 1000L);
                case GAUSSIAN -> new GaussianWeightGenerator(1000, 100);};
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
        checkConstraints();
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
     * Set the strategy used for deciding distribution of weight.
     *
     * @return this object
     */
    @NonNull
    public RandomRosterBuilder withWeightDistributionStrategy(
            @NonNull final WeightDistributionStrategy weightDistributionStrategy) {
        this.weightDistributionStrategy = weightDistributionStrategy;
        checkConstraints();
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
     * Get the private keys for a node. Should only be called after the roster has been built and only if
     * {@link #withRealKeysEnabled(boolean)} was set to true.
     *
     * @param nodeId the node id
     * @return the private keys
     * @throws IllegalStateException if real keys are not being generated or the roster has not been built
     */
    @NonNull
    public KeysAndCerts getPrivateKeys(@NonNull final NodeId nodeId) {
        if (!realKeys) {
            throw new IllegalStateException("Real keys are not being generated");
        }
        if (!privateKeys.containsKey(nodeId)) {
            throw new IllegalStateException("Unknown node ID " + nodeId);
        }
        return privateKeys.get(nodeId);
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
                final PublicStores publicStores = new PublicStores();

                final byte[] masterKey = new byte[64];
                random.nextBytes(masterKey);

                final KeysAndCerts keysAndCerts =
                        KeysAndCerts.generate(nodeId, new byte[] {}, masterKey, new byte[] {}, publicStores);
                privateKeys.put(nodeId, keysAndCerts);

                final SerializableX509Certificate sigCert =
                        new SerializableX509Certificate(publicStores.getCertificate(SIGNING, nodeId));

                addressBuilder.withSigCert(sigCert);

            } catch (final Exception e) {
                throw new RuntimeException();
            }
        }
    }
}
