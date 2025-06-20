// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto.entities;

import static com.hedera.node.app.hapi.fees.usage.crypto.entities.CryptoEntitySizes.CRYPTO_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CryptoEntitySizesTest {
    private final CryptoEntitySizes subject = CRYPTO_ENTITY_SIZES;

    @Test
    void knowsExpectedFixedBytes() {
        // expect:
        assertEquals(3 * BOOL_SIZE + 5 * LONG_SIZE, subject.fixedBytesInAccountRepr());
    }

    @Test
    void knowsTotalBytes() {
        // when:
        final var actual = subject.bytesInTokenAssocRepr();

        // then:
        assertEquals(LONG_SIZE + 3 * BOOL_SIZE, actual);
    }
}
