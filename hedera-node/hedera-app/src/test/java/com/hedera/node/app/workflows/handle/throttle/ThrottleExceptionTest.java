// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.throttle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.junit.jupiter.api.Test;

class ThrottleExceptionTest {
    @Test
    void responseCodesAsExpected() {
        assertThat(ThrottleException.newGasThrottleException().getStatus()).isEqualTo(CONSENSUS_GAS_EXHAUSTED);
        assertThat(ThrottleException.newNativeThrottleException().getStatus()).isEqualTo(THROTTLED_AT_CONSENSUS);
    }
}
