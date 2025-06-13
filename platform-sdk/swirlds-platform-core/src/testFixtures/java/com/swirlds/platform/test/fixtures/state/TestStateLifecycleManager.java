// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.state.merkle.StateLifecycleManagerImpl;

public class TestStateLifecycleManager extends StateLifecycleManagerImpl {
    public TestStateLifecycleManager() {
        super(new NoOpMetrics());
    }
}
