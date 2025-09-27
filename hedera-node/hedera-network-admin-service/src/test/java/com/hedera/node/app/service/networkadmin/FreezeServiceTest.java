// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class FreezeServiceTest {
    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(FreezeService::getInstance)
                .isNotNull();
    }
}
