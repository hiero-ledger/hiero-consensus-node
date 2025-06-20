// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.SignatureException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TransactionParseTest {

    @ParameterizedTest
    @ValueSource(longs = {5, 10})
    void test(final long seed) throws SignatureException {
        final TransactionGenerator generator = new TransactionGenerator(seed);
        final byte[] bytes = generator.generateTransaction();
        Assertions.assertDoesNotThrow(() -> TransactionUtils.parseTransaction(Bytes.wrap(bytes)));
    }
}
