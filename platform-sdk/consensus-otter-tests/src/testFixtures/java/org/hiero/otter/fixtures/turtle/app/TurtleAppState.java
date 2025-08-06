// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.app;

import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.roster.RosterUtils;

public class TurtleAppState extends VirtualMapState<TurtleAppState> implements MerkleNodeState {

    long state;

    public TurtleAppState(@NonNull final PlatformContext platformContext) {
        super(platformContext);
    }

    public TurtleAppState(@NonNull final VirtualMap virtualMap, @NonNull final PlatformContext platformContext) {
        super(virtualMap, platformContext);
    }

    /**
     * Copy constructor.
     *
     * @param from the object to copy
     */
    public TurtleAppState(@NonNull final TurtleAppState from) {
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
    public static TurtleAppState createGenesisState(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final SemanticVersion version) {
        final TestingAppStateInitializer initializer =
                new TestingAppStateInitializer(platformContext.getConfiguration());
        final TurtleAppState state = new TurtleAppState(platformContext);
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
    public TurtleAppState copy() {
        return new TurtleAppState(this);
    }

    @Override
    protected TurtleAppState copyingConstructor() {
        return new TurtleAppState(this);
    }

    @Override
    protected TurtleAppState newInstance(
            @NonNull VirtualMap virtualMap, @NonNull final PlatformContext platformContext) {
        return new TurtleAppState(virtualMap, platformContext);
    }
}
