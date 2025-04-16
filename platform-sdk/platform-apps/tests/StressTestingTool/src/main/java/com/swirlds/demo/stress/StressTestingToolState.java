// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stress;
/*
 * This file is public domain.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * This testing tool simulates configurable processing times for both preHandling and handling for stress testing
 * purposes.
 */
@ConstructableIgnored
public class StressTestingToolState extends MerkleStateRoot<StressTestingToolState> implements MerkleNodeState {
    private static final long CLASS_ID = 0x79900efa3127b6eL;

    /** A running sum of transaction contents */
    private long runningSum = 0;

    public StressTestingToolState() {
        // empty constructor
    }

    private StressTestingToolState(@NonNull final StressTestingToolState sourceState) {
        super(sourceState);
        runningSum = sourceState.runningSum;
        setImmutable(false);
        sourceState.setImmutable(true);
    }

    void incrementRunningSum(final long delta) {
        runningSum += delta;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized StressTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new StressTestingToolState(this);
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
        return ClassVersion.NO_ADDRESS_BOOK_IN_STATE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.NO_ADDRESS_BOOK_IN_STATE;
    }

    @Override
    protected StressTestingToolState copyingConstructor() {
        return new StressTestingToolState(this);
    }

    /**
     * The version history of this class. Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        public static final int NO_ADDRESS_BOOK_IN_STATE = 4;
    }
}
