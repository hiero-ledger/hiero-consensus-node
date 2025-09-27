// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AddressBookServiceTest {

    @Test
    void instanceCantLoadWithoutImplementation() {
        Assertions.assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(AddressBookService::getInstance)
                .isNotNull();
    }
}
