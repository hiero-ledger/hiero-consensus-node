// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.State;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import org.hiero.consensus.roster.RosterUtils;

public class OtterAppState extends VirtualMapState<OtterAppState> implements MerkleNodeState {

    long state;

    public OtterAppState(
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<OtterAppState>, Long> extractRoundFromState) {
        super(platformContext, extractRoundFromState);
    }

    public OtterAppState(
            @NonNull final VirtualMap virtualMap,
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<OtterAppState>, Long> extractRoundFromState) {
        super(virtualMap, platformContext, extractRoundFromState);
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
     * @param platformContext the platform context
     * @param roster          the initial roster stored in the state
     * @param version         the software version to set in the state
     * @return state root
     */
    @NonNull
    public static OtterAppState createGenesisState(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion version) {
        final TestingAppStateInitializer initializer = new TestingAppStateInitializer();
        final Function<State, Long> extractRoundFromState = virtualMapState -> {
            final ConsensusSnapshot consensusSnapshot =
                    DEFAULT_PLATFORM_STATE_FACADE.consensusSnapshotOf(virtualMapState);
            return consensusSnapshot == null ? PlatformStateAccessor.GENESIS_ROUND : consensusSnapshot.round();
        };
        final OtterAppState state = new OtterAppState(platformContext, extractRoundFromState::apply);
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
    protected OtterAppState newInstance(
            @NonNull final VirtualMap virtualMap,
            @NonNull final PlatformContext platformContext,
            @NonNull final Function<VirtualMapState<OtterAppState>, Long> extractRoundFromState) {
        return new OtterAppState(virtualMap, platformContext, extractRoundFromState);
    }
}
