// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;

public class TestStateLifecycleManager<T extends MerkleStateRoot<T>> extends StateLifecycleManagerImpl<T> {
    public TestStateLifecycleManager() {
        super(new NoOpMetrics());
    }
}
