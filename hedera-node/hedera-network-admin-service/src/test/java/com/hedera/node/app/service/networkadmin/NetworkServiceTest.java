// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkServiceTest {
    private final NetworkService subject = registry -> {
        // Intentional no-op
    };

    @Test
    void rpcDefNotNull() {
        Assertions.assertThat(subject.rpcDefinitions()).contains(NetworkServiceDefinition.INSTANCE);
    }

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(NetworkService.NAME);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(NetworkService::getInstance)
                .isNotNull();
    }
}
