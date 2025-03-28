// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import static org.hiero.consensus.utility.test.fixtures.RandomUtils.randomIp;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.SerializableX509Certificate;
import com.swirlds.platform.test.fixtures.crypto.PreGeneratedX509Certs;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.CertificateEncodingException;
import java.util.Objects;
import java.util.Random;

/**
 * A builder for creating random {@link RosterEntry} instances.
 */
public class RandomRosterEntryBuilder {

    private final Random random;
    private Long nodeId;
    private Long weight;
    private Integer port;
    private String hostname;
    private SerializableX509Certificate sigCert;

    private long minimumWeight = 0;
    private long maximumWeight = Long.MAX_VALUE / 1024;

    /**
     * Creates a new {@link RandomRosterEntryBuilder} instance.
     *
     * @param random the random number generator to use
     * @return a new {@link RandomRosterEntryBuilder} instance
     */
    @NonNull
    public static RandomRosterEntryBuilder create(@NonNull final Random random) {
        return new RandomRosterEntryBuilder(random);
    }

    /**
     * Constructor.
     *
     * @param random the random number generator to use
     */
    private RandomRosterEntryBuilder(@NonNull final Random random) {
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Builds a new {@link RosterEntry} instance.
     *
     * @return a new {@link RosterEntry} instance
     */
    @NonNull
    public RosterEntry build() {
        if (nodeId == null) {
            nodeId = random.nextLong(0, Long.MAX_VALUE);
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
            sigCert = PreGeneratedX509Certs.getSigCert(nodeId);
        }

        try {
            return RosterEntry.newBuilder()
                    .nodeId(nodeId)
                    .weight(weight)
                    .gossipCaCertificate(Bytes.wrap(sigCert.getCertificate().getEncoded()))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(hostname)
                            .port(port)
                            .build())
                    .build();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Sets the nodeId for the address.
     *
     * @param nodeId the node ID
     * @return this builder
     */
    @NonNull
    public RandomRosterEntryBuilder withNodeId(final long nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    /**
     * Sets the weight for the address.
     *
     * @param weight the weight
     * @return this builder
     */
    @NonNull
    public RandomRosterEntryBuilder withWeight(final long weight) {
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
    public RandomRosterEntryBuilder withPort(final int port) {
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
    public RandomRosterEntryBuilder withHostname(@NonNull final String hostname) {
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
    public RandomRosterEntryBuilder withSigCert(@NonNull final SerializableX509Certificate sigCert) {
        this.sigCert = Objects.requireNonNull(sigCert);
        return this;
    }

    /**
     * Sets the minimum weight. Ignored if the weight is specifically set. Default 0.
     *
     * @param minimumWeight the minimum weight
     * @return this builder
     */
    @NonNull
    public RandomRosterEntryBuilder withMinimumWeight(final long minimumWeight) {
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
    public RandomRosterEntryBuilder withMaximumWeight(final long maximumWeight) {
        this.maximumWeight = maximumWeight;
        return this;
    }
}
