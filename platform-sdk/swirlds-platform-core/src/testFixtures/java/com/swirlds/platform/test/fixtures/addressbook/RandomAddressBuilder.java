// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import static com.swirlds.common.test.fixtures.RandomUtils.randomIp;
import static com.swirlds.common.test.fixtures.RandomUtils.randomString;

import com.swirlds.platform.crypto.SerializableX509Certificate;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.test.fixtures.crypto.PreGeneratedX509Certs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.node.NodeId;

/**
 * A builder for creating random {@link Address} instances.
 */
public class RandomAddressBuilder {

    private final Random random;
    private NodeId nodeId;
    private Long weight;
    private Integer port;
    private String hostname;
    private SerializableX509Certificate sigCert;
    private SerializableX509Certificate agreeCert;

    private long minimumWeight = 0;
    private long maximumWeight = Long.MAX_VALUE / 1024;

    /**
     * Creates a new {@link RandomAddressBuilder} instance.
     *
     * @param random the random number generator to use
     * @return a new {@link RandomAddressBuilder} instance
     */
    @NonNull
    public static RandomAddressBuilder create(@NonNull final Random random) {
        return new RandomAddressBuilder(random);
    }

    /**
     * Constructor.
     *
     * @param random the random number generator to use
     */
    private RandomAddressBuilder(@NonNull final Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Builds a new {@link Address} instance.
     *
     * @return a new {@link Address} instance
     */
    @NonNull
    public Address build() {

        // Future work: use randotron utility methods once randotron changes merge

        if (nodeId == null) {
            nodeId = NodeId.of(random.nextLong(0, Long.MAX_VALUE));
        }

        if (weight == null) {
            weight = random.nextLong(minimumWeight, maximumWeight);
        }

        if (port == null) {
            port = random.nextInt(1, 65535);
        }

        if (hostname == null) {
            hostname = randomIp(random);
        }

        if (sigCert == null) {
            sigCert = PreGeneratedX509Certs.getSigCert(nodeId.id());
        }

        if (agreeCert == null) {
            agreeCert = PreGeneratedX509Certs.getAgreeCert(nodeId.id());
        }

        return new Address(
                nodeId,
                randomString(random, 8),
                randomString(random, 8),
                weight,
                hostname,
                port,
                hostname,
                port,
                sigCert,
                agreeCert,
                randomString(random, 8));
    }

    /**
     * Sets the {@link NodeId} for the address.
     *
     * @param nodeId the node ID
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withNodeId(@NonNull final NodeId nodeId) {
        this.nodeId = Objects.requireNonNull(nodeId);
        return this;
    }

    /**
     * Sets the weight for the address.
     *
     * @param weight the weight
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withWeight(final long weight) {
        this.weight = weight;
        return this;
    }

    /**
     * Sets the port for the address.
     *
     * @param port the port
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withPort(final int port) {
        this.port = port;
        return this;
    }

    /**
     * Sets the hostname for the address.
     *
     * @param hostname the hostname
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withHostname(@NonNull final String hostname) {
        this.hostname = Objects.requireNonNull(hostname);
        return this;
    }

    /**
     * Sets the sigCert for the address.
     *
     * @param sigCert the sigCert
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withSigCert(@NonNull final SerializableX509Certificate sigCert) {
        this.sigCert = Objects.requireNonNull(sigCert);
        return this;
    }

    /**
     * Sets the agreeCert for the address.
     *
     * @param agreeCert the agreeCert
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withAgreeCert(@NonNull final SerializableX509Certificate agreeCert) {
        this.agreeCert = Objects.requireNonNull(agreeCert);
        return this;
    }

    /**
     * Sets the minimum weight. Ignored if the weight is specifically set. Default 0.
     *
     * @param minimumWeight the minimum weight
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withMinimumWeight(final long minimumWeight) {
        this.minimumWeight = minimumWeight;
        return this;
    }

    /**
     * Sets the maximum weight. Ignored if the weight is specifically set. Default Long.MAX_VALUE / 1024.
     *
     * @param maximumWeight the maximum weight
     * @return this builder
     */
    @NonNull
    public RandomAddressBuilder withMaximumWeight(final long maximumWeight) {
        this.maximumWeight = maximumWeight;
        return this;
    }
}
