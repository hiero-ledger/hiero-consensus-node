// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.generator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RosterWithKeys;
import com.swirlds.platform.test.fixtures.event.signer.EventSigner;
import com.swirlds.platform.test.fixtures.event.signer.RandomEventSigner;
import com.swirlds.platform.test.fixtures.event.signer.RealEventSigner;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Builder for creating {@link SimpleGraphGenerator} instances with optional parameters.
 */
public class SimpleGraphGeneratorBuilder {
    private static final long DEFAULT_SEED = 0L;
    private static final int DEFAULT_MAX_OTHER_PARENTS = 1;
    private static final int DEFAULT_NUM_NODES = 4;

    private Configuration configuration;
    private Time time;
    private Long seed;
    private Integer maxOtherParents;
    private Roster roster;
    private Integer numNodes;
    private boolean realSignatures = false;
    private EventSigner signer;

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static SimpleGraphGeneratorBuilder builder() {
        return new SimpleGraphGeneratorBuilder();
    }

    /**
     * Sets the configuration.
     *
     * @param configuration the configuration
     * @return this builder
     */
    public SimpleGraphGeneratorBuilder configuration(@Nullable final Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    /**
     * Sets the time source.
     *
     * @param time the time source
     * @return this builder
     */
    public SimpleGraphGeneratorBuilder time(@Nullable final Time time) {
        this.time = time;
        return this;
    }

    /**
     * Sets the random seed.
     *
     * @param seed the random seed
     * @return this builder
     */
    public SimpleGraphGeneratorBuilder seed(final long seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Sets the maximum number of other parents for each event.
     *
     * @param maxOtherParents the maximum number of other parents
     * @return this builder
     */
    public SimpleGraphGeneratorBuilder maxOtherParents(final int maxOtherParents) {
        this.maxOtherParents = maxOtherParents;
        return this;
    }

    /**
     * Sets the roster.
     *
     * @param roster the roster
     * @return this builder
     */
    public SimpleGraphGeneratorBuilder roster(@Nullable final Roster roster) {
        if (numNodes != null) {
            throw new IllegalStateException("Cannot set roster when numNodes is already set");
        }
        if (realSignatures) {
            throw new IllegalStateException("Cannot supply roster when realSignatures is enabled");
        }
        this.roster = roster;
        return this;
    }

    /**
     * Sets the number of nodes (will generate a random roster).
     *
     * @param numNodes the number of nodes
     * @return this builder
     */
    public SimpleGraphGeneratorBuilder numNodes(final int numNodes) {
        if (roster != null) {
            throw new IllegalStateException("Cannot set numNodes when roster is already set");
        }
        this.numNodes = numNodes;
        return this;
    }

    public SimpleGraphGeneratorBuilder realSignatures(final boolean realSignatures) {
        if (realSignatures && roster != null) {
            throw new IllegalStateException("Cannot use realSignatures with a supplied roster");
        }
        this.realSignatures = realSignatures;
        return this;
    }

    /**
     * Builds the {@link SimpleGraphGenerator} with the configured parameters.
     *
     * @return a new SimpleGraphGenerator instance
     */
    public SimpleGraphGenerator build() {
        return new SimpleGraphGenerator(
                getConfiguration(),
                getTime(),
                getSeed(),
                getMaxOtherParents(),
                getRoster(),
                getEventSigner());
    }

    private Configuration getConfiguration() {
        return configuration != null ? configuration : new TestConfigBuilder().getOrCreateConfig();
    }

    private Time getTime() {
        return time != null ? time : Time.getCurrent();
    }

    private long getSeed() {
        return seed != null ? seed : DEFAULT_SEED;
    }

    private int getMaxOtherParents() {
        return maxOtherParents != null ? maxOtherParents : DEFAULT_MAX_OTHER_PARENTS;
    }

    private Roster getRoster() {
        if (roster != null) {
            return roster;
        }
        final int nodeCount = numNodes != null ? numNodes : DEFAULT_NUM_NODES;
        if (realSignatures) {
            final RosterWithKeys rosterWithKeys = RandomRosterBuilder.create(Randotron.create(getSeed()))
                    .withSize(nodeCount)
                    .withRealKeysEnabled(true)
                    .buildWithKeys();
            signer = new RealEventSigner(rosterWithKeys);
            return rosterWithKeys.getRoster();
        } else {
            signer = new RandomEventSigner(getSeed());
            return RandomRosterBuilder.create(Randotron.create(getSeed()))
                    .withSize(nodeCount)
                    .withRealKeysEnabled(false)
                    .build();
        }
    }

    private EventSigner getEventSigner() {
        return Objects.requireNonNull(signer, "Signer should have been set in getRoster()");
    }
}
