// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RosterServiceTest {
    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(RosterService::getInstance)
                .isNotNull();
    }
}
