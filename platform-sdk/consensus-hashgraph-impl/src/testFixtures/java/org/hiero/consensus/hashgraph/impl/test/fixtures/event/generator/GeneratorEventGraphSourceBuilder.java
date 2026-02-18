// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.signing.*;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.roster.test.fixtures.RosterWithKeys;
import org.hiero.consensus.test.fixtures.Randotron;

/**
 * Builder for creating {@link GeneratorEventGraphSource} instances with optional parameters.
 */
public class GeneratorEventGraphSourceBuilder {
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
    private boolean populateNgen = false;

    /**
     * Creates a new builder instance.
     *
     * @return a new builder
     */
    public static GeneratorEventGraphSourceBuilder builder() {
        return new GeneratorEventGraphSourceBuilder();
    }

    /**
     * Sets the configuration.
     *
     * @param configuration the configuration
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder configuration(@Nullable final Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    /**
     * Sets the time source.
     *
     * @param time the time source
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder time(@Nullable final Time time) {
        this.time = time;
        return this;
    }

    /**
     * Sets the random seed.
     *
     * @param seed the random seed
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder seed(final long seed) {
        this.seed = seed;
        return this;
    }

    /**
     * Sets the maximum number of other parents for each event.
     *
     * @param maxOtherParents the maximum number of other parents
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder maxOtherParents(final int maxOtherParents) {
        this.maxOtherParents = maxOtherParents;
        return this;
    }

    /**
     * Sets the roster.
     *
     * @param roster the roster
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder roster(@Nullable final Roster roster) {
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
    public GeneratorEventGraphSourceBuilder numNodes(final int numNodes) {
        if (roster != null) {
            throw new IllegalStateException("Cannot set numNodes when roster is already set");
        }
        this.numNodes = numNodes;
        return this;
    }

    /**
     * Sets whether to populate ngen values on generated events.
     *
     * @param populateNgen {@code true} to populate ngen values, {@code false} otherwise
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder populateNgen(final boolean populateNgen) {
        this.populateNgen = populateNgen;
        return this;
    }

    /**
     * Enables or disables real cryptographic signatures for generated events. When enabled, a roster with real keys
     * will be generated and a {@link RealEventSigner} will be used. Cannot be combined with a user-supplied roster.
     *
     * @param realSignatures {@code true} to use real signatures, {@code false} for random signatures
     * @return this builder
     */
    public GeneratorEventGraphSourceBuilder realSignatures(final boolean realSignatures) {
        if (realSignatures && roster != null) {
            throw new IllegalStateException("Cannot use realSignatures with a supplied roster");
        }
        this.realSignatures = realSignatures;
        return this;
    }

    /**
     * Builds the {@link GeneratorEventGraphSource} with the configured parameters.
     *
     * @return a new instance
     */
    public GeneratorEventGraphSource build() {
        final Roster actualRoster;
        final GeneratorEventSigner signer;
        if (roster != null) {
            signer = new RandomEventSigner(getSeed());
            actualRoster = roster;
        } else {
            final int nodeCount = numNodes != null ? numNodes : DEFAULT_NUM_NODES;
            if (realSignatures) {
                final RosterWithKeys rosterWithKeys = RandomRosterBuilder.create(Randotron.create(getSeed()))
                        .withSize(nodeCount)
                        .withRealKeysEnabled(true)
                        .buildWithKeys();
                signer = new RealEventSigner(rosterWithKeys);
                actualRoster = rosterWithKeys.getRoster();
            } else {
                signer = new RandomEventSigner(getSeed());
                actualRoster = RandomRosterBuilder.create(Randotron.create(getSeed()))
                        .withSize(nodeCount)
                        .withRealKeysEnabled(false)
                        .build();
            }
        }

        return new GeneratorEventGraphSource(
                getConfiguration(), getTime(), getSeed(), getMaxOtherParents(), actualRoster, signer, populateNgen);
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
}
