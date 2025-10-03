// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.state;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.spi.EmptyReadableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.Map;

/**
 * A simple migration context for use when initializing a genesis state of an OtterApp.
 */
public class GenesisMigrationContext implements MigrationContext<SemanticVersion> {

    private final Configuration configuration;
    private final WritableStates newStates;

    /**
     * Create a new migration context for genesis.
     *
     * @param configuration the platform configuration
     * @param state the state to migrate from
     * @param serviceName the name of the service being migrated
     */
    public GenesisMigrationContext(
            @NonNull final Configuration configuration, @NonNull final State state, @NonNull final String serviceName) {
        this.configuration = configuration;
        this.newStates = state.getWritableStates(serviceName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long roundNumber() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ReadableStates previousStates() {
        return new EmptyReadableStates();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public WritableStates newStates() {
        return newStates;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Configuration appConfig() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Configuration platformConfig() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public StartupNetworks startupNetworks() {
        throw new UnsupportedOperationException("OtterApp should not need startupNetworks");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void copyAndReleaseOnDiskState(final int stateId) {
        throw new UnsupportedOperationException("OtterApp should not need copyAndReleaseOnDiskState");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public SemanticVersion previousVersion() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Map<String, Object> sharedValues() {
        throw new UnsupportedOperationException("OtterApp should not need sharedValues");
    }

    @Override
    public SemanticVersion getDefaultVersion() {
        return null;
    }

    @Override
    public Comparator<SemanticVersion> getVersionComparator() {
        return SEMANTIC_VERSION_COMPARATOR;
    }
}
