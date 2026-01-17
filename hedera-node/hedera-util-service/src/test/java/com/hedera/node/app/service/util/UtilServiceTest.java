// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UtilServiceTest {
    private final UtilService subject = new UtilService() {};

    @Test
    void rpcDefNotNull() {
        Assertions.assertThat(subject.rpcDefinitions()).contains(UtilServiceDefinition.INSTANCE);
    }

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(UtilService.NAME);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(UtilService::getInstance)
                .isNotNull();
    }
}
