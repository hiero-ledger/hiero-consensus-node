// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing;
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
 * This demo collects statistics on the running of the network and consensus systems. It writes them to the
 * screen, and also saves them to disk in a comma separated value (.csv) file. Optionally, it can also put a
 * sequence number into each transaction, and check if any are lost, or delayed too long. Each transaction
 * is 100 random bytes. So StatsSigningDemoState.handleTransaction doesn't actually do anything, other than the
 * optional sequence number check.
 */
@ConstructableIgnored
public class StatsSigningTestingToolState extends MerkleStateRoot<StatsSigningTestingToolState>
        implements MerkleNodeState {

    private static final long CLASS_ID = 0x79900efa3127b6eL;

    /** A running sum of transaction contents */
    private long runningSum = 0;

    public StatsSigningTestingToolState() {
        // no-op
    }

    private StatsSigningTestingToolState(@NonNull final StatsSigningTestingToolState sourceState) {
        super(sourceState);
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
    public synchronized StatsSigningTestingToolState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new StatsSigningTestingToolState(this);
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
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    protected StatsSigningTestingToolState copyingConstructor() {
        return new StatsSigningTestingToolState();
    }

    /**
     * The version history of this class.
     * Versions that have been released must NEVER be given a different value.
     */
    private static class ClassVersion {
        /**
         * In this version, serialization was performed by copyTo/copyToExtra and deserialization was performed by
         * copyFrom/copyFromExtra. This version is not supported by later deserialization methods and must be handled
         * specially by the platform.
         */
        public static final int ORIGINAL = 1;
        /**
         * In this version, serialization was performed by serialize/deserialize.
         */
        public static final int MIGRATE_TO_SERIALIZABLE = 2;

        /**
         * In this version, a transaction counter was added to the state.
         */
        public static final int KEEP_STATE = 3;

        public static final int NO_ADDRESS_BOOK_IN_STATE = 4;
    }
}
