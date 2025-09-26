// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TokenServiceTest {
    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(TokenService::getInstance)
                .isNotNull();
    }
}
