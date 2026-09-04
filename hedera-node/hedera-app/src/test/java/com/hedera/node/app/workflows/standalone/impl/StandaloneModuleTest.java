// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.standalone.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.dispatch.NoOpNodeControlledPayerGuard;
import org.junit.jupiter.api.Test;

class StandaloneModuleTest {
    @Test
    void standaloneExecutorBindsANoOpNodeControlledPayerGuard() {
        // The standalone transaction executor must bind the no-op guard so DispatchValidator's NODE-payer guard stays
        // exempt for its sig-map-less, caller-payer dispatches (e.g. Mirror Node gas estimation / eth_call). A
        // regression to the live guard here would reject those and break the Mirror Node executor.
        final var guard = StandaloneModule.provideNodeControlledPayerGuard();
        assertInstanceOf(NoOpNodeControlledPayerGuard.class, guard);
        assertFalse(guard.rejectsForeignNodePayer(mock(Dispatch.class)));
    }
}
