// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle.app;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;

public class TurtleAppState extends MerkleStateRoot<TurtleAppState> implements MerkleNodeState {

    private static final long CLASS_ID = 0x107F552E92071390L;

    private static final class ClassVersion {

        public static final int ORIGINAL = 1;
    }

    long state;

    public TurtleAppState() {
        // empty
    }

    /**
     * Copy constructor.
     *
     * @param from the object to copy
     */
    private TurtleAppState(@NonNull final TurtleAppState from) {
        super(from);
        this.state = from.state;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public TurtleAppState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new TurtleAppState(this);
    }

    @Override
    protected TurtleAppState copyingConstructor() {
        return new TurtleAppState(this);
    }

    /**
     * Creates a merkle node to act as a state tree root.
     *
     * @return merkle tree root
     */
    @NonNull
    public static MerkleNodeState getStateRootNode(@NonNull final Configuration configuration) {
        final TestingAppStateInitializer initializer = new TestingAppStateInitializer(configuration);
        final MerkleNodeState state = new TurtleAppState();
        initializer.initStates(state);
        return state;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ORIGINAL;
    }
}
