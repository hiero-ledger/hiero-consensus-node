// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.StateMetadata;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class TestMerkleStateRoot extends MerkleStateRoot<TestMerkleStateRoot> implements MerkleNodeState {

    private static final long CLASS_ID = 0x8e300b0dfdafbb1cL;

    public TestMerkleStateRoot() {
        // empty constructor
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
}
