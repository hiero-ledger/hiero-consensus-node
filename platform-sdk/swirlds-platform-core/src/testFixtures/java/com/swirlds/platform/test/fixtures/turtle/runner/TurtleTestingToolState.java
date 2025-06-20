// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.runner;

import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.*;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A simple testing application intended for use with TURTLE.
 * <pre>
 *   _______    ֥  ֖       ֥  ֖    _______
 * 〈 Tᴜʀᴛʟᴇ ᐳ﹙⚬◡°﹚   ﹙°◡⚬﹚ᐸ ᴇʟᴛʀᴜT 〉
 *   ﹉∏﹉∏﹉                   ﹉∏﹉∏﹉
 * </pre>
 */
public class TurtleTestingToolState extends VirtualMapState<TurtleTestingToolState> implements MerkleNodeState {

    long state;

    public TurtleTestingToolState(@NonNull final Configuration configuration, @NonNull final Metrics metrics) {
        super(configuration, metrics);
    }

    public TurtleTestingToolState(@NonNull final VirtualMap virtualMap) {
        super(virtualMap);
    }

    /**
     * Copy constructor.
     *
     * @param from the object to copy
     */
    private TurtleTestingToolState(@NonNull final TurtleTestingToolState from) {
        super(from);
        this.state = from.state;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TurtleTestingToolState copy() {
        return new TurtleTestingToolState(this);
    }

    @Override
    protected TurtleTestingToolState copyingConstructor() {
        return new TurtleTestingToolState(this);
    }

    @Override
    protected TurtleTestingToolState newInstance(@NonNull VirtualMap virtualMap) {
        return new TurtleTestingToolState(virtualMap);
    }

    /**
     * Creates a merkle node to act as a state tree root.
     *
     * @return merkle tree root
     */
    @NonNull
    public static MerkleNodeState getStateRootNode(@NonNull final Metrics metrics) {
        final MerkleNodeState state = new TurtleTestingToolState(CONFIGURATION, metrics);
        TestingAppStateInitializer.DEFAULT.initPlatformState(state);
        TestingAppStateInitializer.DEFAULT.initRosterState(state);

        return state;
    }
}
