// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.exec.utils.HederaGasCounter;
import org.junit.jupiter.api.Test;

class HederaGasCounterTest {

    @Test
    void constructorSetsInitialGas() {
        HederaGasCounter counter = new HederaGasCounter(100L);
        assertEquals(100L, counter.getGasConsumed());
    }

    @Test
    void incrementGasConsumedAddsGas() {
        HederaGasCounter counter = new HederaGasCounter(50L);
        counter.incrementGasConsumed(25L);
        assertEquals(75L, counter.getGasConsumed());
    }

    @Test
    void incrementGasConsumedByZero() {
        HederaGasCounter counter = new HederaGasCounter(10L);
        counter.incrementGasConsumed(0L);
        assertEquals(10L, counter.getGasConsumed());
    }

    @Test
    void multipleIncrements() {
        HederaGasCounter counter = new HederaGasCounter(0L);
        counter.incrementGasConsumed(10L);
        counter.incrementGasConsumed(20L);
        counter.incrementGasConsumed(5L);
        assertEquals(35L, counter.getGasConsumed());
    }
}
