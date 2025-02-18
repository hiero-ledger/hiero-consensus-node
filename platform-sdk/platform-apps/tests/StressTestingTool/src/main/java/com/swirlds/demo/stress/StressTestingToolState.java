/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;

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
