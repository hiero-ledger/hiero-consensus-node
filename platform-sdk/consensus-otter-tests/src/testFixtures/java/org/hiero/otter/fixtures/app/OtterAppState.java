// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.hiero.otter.fixtures.app.state.OtterStateInitializer.initOtterAppState;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
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
     * Creates an initialized {@code OtterAppState}.
     *
     * @param configuration the configuration used during initialization
     * @param roster        the initial roster stored in the state
     * @param metrics       the metrics to be registered with virtual map
     * @param version       the software version to set in the state
     * @return state root
     */
    @NonNull
    public static OtterAppState createGenesisState(
            @NonNull final Configuration configuration,
            @NonNull final Roster roster,
            @NonNull final Metrics metrics,
            @NonNull final SemanticVersion version,
            @NonNull final List<OtterService> services) {

        final OtterAppState state = new OtterAppState(CONFIGURATION, metrics);

        initOtterAppState(state, version, services);
        RosterUtils.setActiveRoster(state, roster, 0L);

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
    protected OtterAppState newInstance(@NonNull final VirtualMap virtualMap) {
        return new OtterAppState(virtualMap);
    }

    /**
     * Commit the state of all services.
     */
    public void commitState() {
        this.getServices().keySet().stream()
                .map(this::getWritableStates)
                .map(writableStates -> (CommittableWritableStates) writableStates)
                .forEach(CommittableWritableStates::commit);
    }
}
