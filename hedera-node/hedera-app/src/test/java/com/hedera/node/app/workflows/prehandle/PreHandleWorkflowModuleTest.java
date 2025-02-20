// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.ForkJoinPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreHandleWorkflowModuleTest {
    @Test
    void usesComponentsToGetExecutorService() {
        final var execService = PreHandleWorkflowInjectionModule.provideExecutorService();
        assertInstanceOf(ForkJoinPool.class, execService);
    }
}
