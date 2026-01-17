// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ConsensusServiceTest {
    private final ConsensusService subject = registry -> {
        // Intentional no-op
    };

    @Test
    void rpcDefNotNull() {
        Assertions.assertThat(subject.rpcDefinitions()).contains(ConsensusServiceDefinition.INSTANCE);
    }

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(ConsensusService.NAME);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(ConsensusService::getInstance)
                .isNotNull();
    }
}
