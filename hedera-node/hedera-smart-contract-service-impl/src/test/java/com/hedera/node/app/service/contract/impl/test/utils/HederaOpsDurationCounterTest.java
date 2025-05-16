// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.exec.utils.HederaOpsDurationCounter;
import org.junit.jupiter.api.Test;

class HederaOpsDurationCounterTest {

    @Test
    void constructorSetsInitialGas() {
        HederaOpsDurationCounter counter = new HederaOpsDurationCounter(100L);
        assertEquals(100L, counter.getOpsDurationCounter());
    }

    @Test
    void incrementGasConsumedAddsGas() {
        HederaOpsDurationCounter counter = new HederaOpsDurationCounter(50L);
        counter.incrementOpsDuration(25L);
        assertEquals(75L, counter.getOpsDurationCounter());
    }

    @Test
    void incrementOpsDurationByZero() {
        HederaOpsDurationCounter counter = new HederaOpsDurationCounter(10L);
        counter.incrementOpsDuration(0L);
        assertEquals(10L, counter.getOpsDurationCounter());
    }

    @Test
    void multipleIncrements() {
        HederaOpsDurationCounter counter = new HederaOpsDurationCounter(0L);
        counter.incrementOpsDuration(10L);
        counter.incrementOpsDuration(20L);
        counter.incrementOpsDuration(5L);
        assertEquals(35L, counter.getOpsDurationCounter());
    }
}
