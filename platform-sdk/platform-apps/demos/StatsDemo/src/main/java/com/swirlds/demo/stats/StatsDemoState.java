// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats;
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

import static com.swirlds.demo.stats.StatsDemoMain.CONFIGURATION;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.crypto.Hash;

/**
 * This demo collects statistics on the running of the network and consensus systems. It writes them to the
 * screen, and also saves them to disk in a comma separated value (.csv) file. Optionally, it can also put a
 * sequence number into each transaction, and check if any are lost, or delayed too long. Each transaction
 * is 100 random bytes. So StatsDemoState.handleTransaction doesn't actually do anything, other than the
 * optional sequence number check.
 */
@ConstructableIgnored
public class StatsDemoState extends MerkleStateRoot<StatsDemoState> implements MerkleNodeState {

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
    }

    private static final long CLASS_ID = 0xc550a1cd94e91ca3L;

    public StatsDemoState() {
        super(new NoOpMetrics(), Time.getCurrent(), MerkleCryptographyFactory.create(CONFIGURATION));
    }

    private StatsDemoState(final StatsDemoState sourceState) {
        super(sourceState);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected long getRound() {
        return DEFAULT_PLATFORM_STATE_FACADE.roundOf(this);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public synchronized StatsDemoState copy() {
        throwIfImmutable();
        setImmutable(true);
        return new StatsDemoState(this);
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
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }

    @Override
    protected StatsDemoState copyingConstructor() {
        return new StatsDemoState(this);
    }

    @Override
    public long singletonPath(final int stateId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long queueElementPath(final int stateId, @NonNull Bytes expectedValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long kvPath(final int stateId, @NonNull Bytes key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Hash getHashForPath(long path) {
        throw new UnsupportedOperationException();
    }
}
