// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot.CLASS_ID;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.PlatformStateAccessor;
import com.swirlds.platform.test.fixtures.constructable.constructors.TestMerkleStateRootConstructor;
import com.swirlds.state.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.constructable.ConstructableClass;

/**
 * @deprecated This test class is only required for the testing of MerkleStateRoot class and will be removed together with that class.
 */
@Deprecated
@ConstructableClass(value = CLASS_ID, constructorType = TestMerkleStateRootConstructor.class)
public class TestMerkleStateRoot extends MerkleStateRoot<TestMerkleStateRoot> implements MerkleNodeState {

    static final long CLASS_ID = 0x8e300b0dfdafbb1cL;

    public TestMerkleStateRoot(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final MerkleCryptography merkleCryptography) {
        super(configuration, metrics, time, merkleCryptography);
    }

    protected TestMerkleStateRoot(TestMerkleStateRoot that) {
        super(that);
    }

    @Override
    protected TestMerkleStateRoot copyingConstructor() {
        return new TestMerkleStateRoot(this);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    protected long getRound() {
        final ConsensusSnapshot consensusSnapshot = TEST_PLATFORM_STATE_FACADE.consensusSnapshotOf(this);
        return consensusSnapshot == null ? PlatformStateAccessor.GENESIS_ROUND : consensusSnapshot.round();
    }
}
