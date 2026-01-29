// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event.generator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Random;

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
        if(numNodes != null) {
            throw new IllegalStateException("Cannot set roster when numNodes is already set");
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
        if(roster != null) {
            throw new IllegalStateException("Cannot set numNodes when roster is already set");
        }
        this.numNodes = numNodes;
        return this;
    }

    /**
     * Builds the {@link SimpleGraphGenerator} with the configured parameters.
     *
     * @return a new SimpleGraphGenerator instance
     */
    public SimpleGraphGenerator build() {
        // Apply defaults
        final Configuration actualConfiguration = configuration != null
                ? configuration
                : new TestConfigBuilder().getOrCreateConfig();

        final Time actualTime = time != null ? time : Time.getCurrent();

        final long actualSeed = seed != null ? seed : DEFAULT_SEED;

        final int actualMaxOtherParents = maxOtherParents != null ? maxOtherParents : DEFAULT_MAX_OTHER_PARENTS;

        // Determine the roster
        final Roster actualRoster;
        if (roster != null) {
            actualRoster = roster;
        } else {
            final int nodeCount = numNodes != null ? numNodes : DEFAULT_NUM_NODES;
            actualRoster = RandomRosterBuilder.create(Randotron.create(actualSeed))
                    .withSize(nodeCount)
                    .withRealKeysEnabled(false)
                    .build();
        }

        final Randotron randotron = Randotron.create(actualSeed);

        return new SimpleGraphGenerator(
                actualConfiguration,
                actualTime,
                actualSeed,
                actualMaxOtherParents,
                actualRoster,
                ue->randotron.nextSignatureBytes());
    }
}
