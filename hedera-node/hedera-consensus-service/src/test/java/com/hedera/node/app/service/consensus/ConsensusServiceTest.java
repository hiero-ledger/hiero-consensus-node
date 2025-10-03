// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ConsensusServiceTest {
    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(ConsensusService::getInstance)
                .isNotNull();
    }
}
