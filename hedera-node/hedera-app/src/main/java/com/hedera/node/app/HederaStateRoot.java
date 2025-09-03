// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.hedera.node.app.HederaStateRoot.CLASS_ID;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.node.app.constructable.constructors.HederaStateRootConstructor;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.state.State;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableClass;

/**
 * This class sole purpose is to extend the {@link MerkleStateRoot} class and implement the {@link MerkleNodeState}.
 * Technically, {@link MerkleStateRoot} is already implementing {@link State} and {@link MerkleNode} but it does not
 * implement the {@link MerkleNodeState} interface. This class is merely a connector of these two interfaces.
 *
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 */
@Deprecated
@ConstructableClass(value = CLASS_ID, constructorType = HederaStateRootConstructor.class)
public class HederaStateRoot extends MerkleStateRoot<HederaStateRoot> implements MerkleNodeState {

    static final long CLASS_ID = 0x8e300b0dfdafbb1aL;

    public HederaStateRoot(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final MerkleCryptography merkleCryptography) {
        super(configuration, metrics, time, merkleCryptography);
    }

    protected HederaStateRoot(@NonNull HederaStateRoot from) {
        super(from);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected HederaStateRoot copyingConstructor() {
        return new HederaStateRoot(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public long getRound() {
        final ConsensusSnapshot consensusSnapshot = DEFAULT_PLATFORM_STATE_FACADE.consensusSnapshotOf(this);
        return consensusSnapshot == null ? PlatformStateAccessor.GENESIS_ROUND : consensusSnapshot.round();
    }
}
