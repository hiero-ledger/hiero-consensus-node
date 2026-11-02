// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.ContractExecutionDepth;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContractExecutionDepthTest {
    @AfterEach
    void tearDown() {
        // Reset the depth so a failed test can't affect other tests
        while (ContractExecutionDepth.isNested()) {
            ContractExecutionDepth.exit();
        }
        ContractExecutionDepth.exit();
    }

    @Test
    void notNestedByDefault() {
        assertThat(ContractExecutionDepth.isNested()).isFalse();
    }

    @Test
    void outermostExecutionIsNotNested() {
        ContractExecutionDepth.enter();
        assertThat(ContractExecutionDepth.isNested()).isFalse();
        ContractExecutionDepth.exit();
        assertThat(ContractExecutionDepth.isNested()).isFalse();
    }

    @Test
    void innerExecutionIsNested() {
        ContractExecutionDepth.enter();
        ContractExecutionDepth.enter();
        assertThat(ContractExecutionDepth.isNested()).isTrue();
        ContractExecutionDepth.exit();
        assertThat(ContractExecutionDepth.isNested()).isFalse();
        ContractExecutionDepth.exit();
        assertThat(ContractExecutionDepth.isNested()).isFalse();
    }

    @Test
    void depthResetsAfterBalancedExitAndExtraExitIsHarmless() {
        ContractExecutionDepth.enter();
        ContractExecutionDepth.exit();
        // An extra exit must leave the depth at zero
        ContractExecutionDepth.exit();
        ContractExecutionDepth.enter();
        assertThat(ContractExecutionDepth.isNested()).isFalse();
        ContractExecutionDepth.exit();
    }

    @Test
    void depthIsTrackedPerThread() throws Exception {
        final var firstThreadIsNested = new CountDownLatch(1);
        final var secondThreadIsDone = new CountDownLatch(1);
        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var firstThreadStaysNested = executor.submit(() -> {
                ContractExecutionDepth.enter();
                ContractExecutionDepth.enter();
                try {
                    firstThreadIsNested.countDown();
                    assertThat(secondThreadIsDone.await(10, TimeUnit.SECONDS)).isTrue();
                    return ContractExecutionDepth.isNested();
                } finally {
                    ContractExecutionDepth.exit();
                    ContractExecutionDepth.exit();
                }
            });
            final var secondThreadSeesNested = executor.submit(() -> {
                assertThat(firstThreadIsNested.await(10, TimeUnit.SECONDS)).isTrue();
                ContractExecutionDepth.enter();
                try {
                    return ContractExecutionDepth.isNested();
                } finally {
                    ContractExecutionDepth.exit();
                    secondThreadIsDone.countDown();
                }
            });
            // An outermost execution on one thread is not nested while another thread is at depth 2
            assertThat(secondThreadSeesNested.get(10, TimeUnit.SECONDS)).isFalse();
            // The other thread's enter and exit leave this thread's depth unchanged
            assertThat(firstThreadStaysNested.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }
}
