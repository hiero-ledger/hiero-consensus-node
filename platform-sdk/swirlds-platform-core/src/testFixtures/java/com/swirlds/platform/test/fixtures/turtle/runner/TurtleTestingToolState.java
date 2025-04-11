// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.runner;

import static com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler.FAKE_CONSENSUS_STATE_EVENT_HANDLER;

import com.swirlds.platform.state.*;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A simple testing application intended for use with TURTLE.
 * <pre>
 *   _______    ֥  ֖       ֥  ֖    _______
 * 〈 Tᴜʀᴛʟᴇ ᐳ﹙⚬◡°﹚   ﹙°◡⚬﹚ᐸ ᴇʟᴛʀᴜT 〉
 *   ﹉∏﹉∏﹉                   ﹉∏﹉∏﹉
 * </pre>
 */
public class TurtleTestingToolState extends MerkleStateRoot<TurtleTestingToolState> implements MerkleNodeState {

    private static final long CLASS_ID = 0xa49b3822a4136ac6L;

    private static final class ClassVersion {

        public static final int ORIGINAL = 1;
    }

    long state;

    public TurtleTestingToolState() {
        // empty
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
    public TurtleTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new TurtleTestingToolState(this);
    }

    @Override
    protected TurtleTestingToolState copyingConstructor() {
        return new TurtleTestingToolState(this);
    }

    /**
     * Creates a merkle node to act as a state tree root.
     *
     * @return merkle tree root
     */
    @NonNull
    public static MerkleNodeState getStateRootNode() {
        final MerkleNodeState state = new TurtleTestingToolState();
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initPlatformState(state);
        FAKE_CONSENSUS_STATE_EVENT_HANDLER.initRosterState(state);

        return state;
    }
}
