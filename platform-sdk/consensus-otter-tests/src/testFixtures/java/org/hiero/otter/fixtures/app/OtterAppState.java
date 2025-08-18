// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.roster.RosterUtils;

public class OtterAppState extends VirtualMapState<OtterAppState> implements MerkleNodeState {

    long state;

    public OtterAppState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public OtterAppState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    /**
     * Copy constructor.
     *
     * @param from the object to copy
     */
    public OtterAppState(@NonNull final OtterAppState from) {
        super(from);
        this.state = from.state;
    }

    /**
     * Creates an initialized {@code TurtleAppState}.
     *
     * @param roster        the initial roster stored in the state
     * @param metrics       the metrics to be registered with virtual map
     * @param version       the software version to set in the state
     * @return state root
     */
    @NonNull
    public static OtterAppState createGenesisState(
            @NonNull final Roster roster, @NonNull final Metrics metrics, @NonNull final SemanticVersion version) {
        final TestingAppStateInitializer initializer = new TestingAppStateInitializer();
        final OtterAppState state = new OtterAppState(CONFIGURATION, metrics);
        initializer.initStates(state);
        RosterUtils.setActiveRoster(state, roster, 0L);
        DEFAULT_PLATFORM_STATE_FACADE.setCreationSoftwareVersionTo(state, version);
        return state;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OtterAppState copy() {
        return new OtterAppState(this);
    }

    @Override
    protected OtterAppState copyingConstructor() {
        return new OtterAppState(this);
    }

    @Override
    protected OtterAppState newInstance(@NonNull VirtualMap virtualMap) {
        return new OtterAppState(virtualMap);
    }
}
