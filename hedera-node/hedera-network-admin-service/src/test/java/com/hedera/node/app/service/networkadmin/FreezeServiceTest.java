// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FreezeServiceTest {
    private final FreezeService subject = registry -> {
        // Intentional no-op
    };

    @Test
    void rpcDefNotNull() {
        Assertions.assertThat(subject.rpcDefinitions()).contains(FreezeServiceDefinition.INSTANCE);
    }

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(FreezeService.NAME);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(FreezeService::getInstance)
                .isNotNull();
    }
}
