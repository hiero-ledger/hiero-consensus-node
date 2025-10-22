// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.roster;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class RosterServiceTest {
    private final RosterService subject = registry -> {
        // Intentional no-op
    };

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(RosterService.NAME);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(RosterService::getInstance)
                .isNotNull();
    }
}
