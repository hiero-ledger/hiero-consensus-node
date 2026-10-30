// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.node.app.service.contract.impl.exec.ContractExecutionDepth;
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
}
