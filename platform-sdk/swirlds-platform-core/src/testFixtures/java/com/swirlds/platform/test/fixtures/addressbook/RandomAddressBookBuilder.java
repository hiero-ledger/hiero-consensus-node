// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import static com.swirlds.platform.crypto.KeyCertPurpose.AGREEMENT;
import static com.swirlds.platform.crypto.KeyCertPurpose.SIGNING;

import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PublicStores;
import com.swirlds.platform.crypto.SerializableX509Certificate;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility for generating a random address book.
 */
public class RandomAddressBookBuilder {

    /**
     * All randomness comes from this.
     */
    private final Random random;

    /**
     * The number of addresses to put into the address book.
     */
    private int size = 4;

    /**
     * Describes different ways that the random address book has its weight distributed if the custom strategy lambda is
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
     * The average weight. Used directly if using {@link WeightDistributionStrategy#BALANCED}, used as mean if using
     * {@link WeightDistributionStrategy#GAUSSIAN}.
     */
    private long averageWeight = 1000;

    /**
     * The standard deviation of the weight, ignored if distribution strategy is not
     * {@link WeightDistributionStrategy#GAUSSIAN}.
     */
    private long weightStandardDeviation = 100;

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
     * Create a new random address book generator.
     *
     * @param random a source of randomness
     * @return a new random address book generator
     */
    @NonNull
    public static RandomAddressBookBuilder create(@NonNull final Random random) {
        return new RandomAddressBookBuilder(random);
    }

    /**
     * Constructor.
     *
     * @param random a source of randomness
     */
    private RandomAddressBookBuilder(@NonNull final Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Build a random address book given the provided configuration.
     */
    @NonNull
    public AddressBook build() {
        final AddressBook addressBook = new AddressBook();
        addressBook.setRound(Math.abs(random.nextLong()));

        if (maximumWeight == null && size > 0) {
            // We don't want the total weight to overflow a long
            maximumWeight = Long.MAX_VALUE / size;
        }

        for (int index = 0; index < size; index++) {
            final NodeId nodeId = getNextNodeId();
            final RandomAddressBuilder addressBuilder =
                    RandomAddressBuilder.create(random).withNodeId(nodeId).withWeight(getNextWeight());

            generateKeys(nodeId, addressBuilder);
            addressBook.add(addressBuilder.build());
        }

        return addressBook;
    }

    /**
     * Set the size of the address book.
     *
     * @return this object
     */
    @NonNull
    public RandomAddressBookBuilder withSize(final int size) {
        this.size = size;
        return this;
    }

    /**
     * Set the average weight for an address. If the weight distribution strategy is
     * {@link WeightDistributionStrategy#BALANCED}, all addresses will have this weight. If the weight distribution
     * strategy is {@link WeightDistributionStrategy#GAUSSIAN}, this will be the mean weight.
     *
     * @return this object
     */
    @NonNull
    public RandomAddressBookBuilder withAverageWeight(final long averageWeight) {
        this.averageWeight = averageWeight;
        return this;
    }

    /**
     * Set the standard deviation for the weight for an address. Ignored unless the weight distribution strategy is
     * {@link WeightDistributionStrategy#GAUSSIAN}.
     *
     * @return this object
     */
    @NonNull
    public RandomAddressBookBuilder withWeightStandardDeviation(final long weightStandardDeviation) {
        this.weightStandardDeviation = weightStandardDeviation;
        return this;
    }

    /**
     * Set the minimum weight for an address. Overrides the weight generation strategy.
     *
     * @return this object
     */
    @NonNull
    public RandomAddressBookBuilder withMinimumWeight(final long minimumWeight) {
        this.minimumWeight = minimumWeight;
        return this;
    }

    /**
     * Set the maximum weight for an address. Overrides the weight generation strategy.
     *
     * @return this object
     */
    @NonNull
    public RandomAddressBookBuilder withMaximumWeight(final long maximumWeight) {
        this.maximumWeight = maximumWeight;
        return this;
    }

    /**
     * Set the strategy used for deciding distribution of weight.
     *
     * @return this object
     */
    @NonNull
    public RandomAddressBookBuilder withWeightDistributionStrategy(
            final WeightDistributionStrategy weightDistributionStrategy) {

        this.weightDistributionStrategy = weightDistributionStrategy;
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
    public RandomAddressBookBuilder withRealKeysEnabled(final boolean realKeysEnabled) {
        this.realKeys = realKeysEnabled;
        return this;
    }

    /**
     * Get the private keys for a node. Should only be called after the address book has been built and only if
     * {@link #withRealKeysEnabled(boolean)} was set to true.
     *
     * @param nodeId the node id
     * @return the private keys
     * @throws IllegalStateException if real keys are not being generated or the address book has not been built
     */
    @NonNull
    public KeysAndCerts getPrivateKeys(final NodeId nodeId) {
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

    /**
     * Generate the next weight for the next address.
     */
    private long getNextWeight() {
        final long unboundedWeight;
        switch (weightDistributionStrategy) {
            case BALANCED -> unboundedWeight = averageWeight;
            case GAUSSIAN -> unboundedWeight =
                    Math.max(0, (long) (averageWeight + random.nextGaussian() * weightStandardDeviation));
            default -> throw new IllegalStateException("Unexpected value: " + weightDistributionStrategy);
        }

        return Math.min(maximumWeight, Math.max(minimumWeight, unboundedWeight));
    }

    /**
     * Generate the cryptographic keys for a node.
     */
    private void generateKeys(@NonNull final NodeId nodeId, @NonNull final RandomAddressBuilder addressBuilder) {
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
                final SerializableX509Certificate agrCert =
                        new SerializableX509Certificate(publicStores.getCertificate(AGREEMENT, nodeId));

                addressBuilder.withSigCert(sigCert).withAgreeCert(agrCert);

            } catch (final Exception e) {
                throw new RuntimeException();
            }
        }
    }
}
