// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.test.fixtures.Randotron;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.junit.jupiter.api.Test;

class TransactionWrapperTest {

    @Test
    void testGetSize() {
        final Randotron rand = Randotron.create();
        final Bytes tx = Bytes.wrap(rand.nextByteArray(1_024));
        final TransactionWrapper txWrapper = new TransactionWrapper(tx);

        assertEquals(1_024L, txWrapper.getSize());
    }
}
