// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AddressBookServiceTest {

    private final AddressBookService subject = registry -> {
        // Intentional no-op
    };

    @Test
    void rpcDefNotNull() {
        Assertions.assertThat(subject.rpcDefinitions()).contains(AddressBookServiceDefinition.INSTANCE);
    }

    @Test
    void nameMatches() {
        Assertions.assertThat(subject.getServiceName()).isEqualTo(AddressBookService.NAME);
    }

    @Test
    void correctMigrationOrder() {
        Assertions.assertThat(subject.migrationOrder()).isEqualTo(1);
    }

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(AddressBookService::getInstance)
                .isNotNull();
    }
}
