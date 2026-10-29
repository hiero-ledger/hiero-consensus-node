// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.ContractExecutionDepth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContractExecutionDepthTest {
    @AfterEach
    void tearDown() {
        // Leave the thread-local clean for other tests sharing this JVM
        while (ContractExecutionDepth.isNested()) {
            ContractExecutionDepth.exit();
        }
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
        // An extra exit must not drive the depth negative or otherwise corrupt the next execution
        ContractExecutionDepth.exit();
        ContractExecutionDepth.enter();
        assertThat(ContractExecutionDepth.isNested()).isFalse();
        ContractExecutionDepth.exit();
    }
}
