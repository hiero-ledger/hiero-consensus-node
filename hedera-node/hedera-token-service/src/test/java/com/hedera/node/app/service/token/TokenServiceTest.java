// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TokenServiceTest {
    private final TokenService subject = registry -> {
        // Intentional no-op
    };

    @Test
    void rpcDefNotNull() {
        Assertions.assertThat(subject.rpcDefinitions()).contains(TokenServiceDefinition.INSTANCE);
    }

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(TokenService.NAME);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(TokenService::getInstance)
                .isNotNull();
    }
}
