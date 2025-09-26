// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilServiceTest {
    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(UtilService::getInstance)
                .isNotNull();
    }
}
