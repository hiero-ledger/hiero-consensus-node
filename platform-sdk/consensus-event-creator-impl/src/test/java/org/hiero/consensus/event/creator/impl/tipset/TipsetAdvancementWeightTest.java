// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static org.hiero.consensus.event.creator.impl.tipset.TipsetAdvancementWeight.ZERO_ADVANCEMENT_WEIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TipsetAdvancementWeight Tests")
class TipsetAdvancementWeightTest {

    @Test
    @DisplayName("plus() Test")
    void plusTest() {
        assertEquals(ZERO_ADVANCEMENT_WEIGHT, ZERO_ADVANCEMENT_WEIGHT.plus(ZERO_ADVANCEMENT_WEIGHT));
        assertEquals(
                TipsetAdvancementWeight.of(1234, 4321),
                TipsetAdvancementWeight.of(1234, 4321).plus(ZERO_ADVANCEMENT_WEIGHT));
        assertEquals(
                TipsetAdvancementWeight.of(579, 975),
                TipsetAdvancementWeight.of(123, 321).plus(TipsetAdvancementWeight.of(456, 654)));
    }

    @Test
    @DisplayName("minus() Test")
    void minusTest() {
        assertEquals(ZERO_ADVANCEMENT_WEIGHT, ZERO_ADVANCEMENT_WEIGHT.minus(ZERO_ADVANCEMENT_WEIGHT));
        assertEquals(
                TipsetAdvancementWeight.of(1234, 4321),
                TipsetAdvancementWeight.of(1234, 4321).minus(ZERO_ADVANCEMENT_WEIGHT));
        assertEquals(
                TipsetAdvancementWeight.of(579, 975),
                TipsetAdvancementWeight.of(123, 321).minus(TipsetAdvancementWeight.of(-456, -654)));
    }

    @Test
    @DisplayName("isGreaterThan() Test")
    void isGreaterThanTest() {
        assertFalse(ZERO_ADVANCEMENT_WEIGHT.isGreaterThan(ZERO_ADVANCEMENT_WEIGHT));
        assertFalse(ZERO_ADVANCEMENT_WEIGHT.isGreaterThan(TipsetAdvancementWeight.of(0, 1)));
        assertFalse(ZERO_ADVANCEMENT_WEIGHT.isGreaterThan(TipsetAdvancementWeight.of(1, 0)));
        assertFalse(ZERO_ADVANCEMENT_WEIGHT.isGreaterThan(TipsetAdvancementWeight.of(1, 1)));
        assertTrue(TipsetAdvancementWeight.of(1, 1).isGreaterThan(ZERO_ADVANCEMENT_WEIGHT));
        assertTrue(TipsetAdvancementWeight.of(1, 0).isGreaterThan(ZERO_ADVANCEMENT_WEIGHT));
        assertTrue(TipsetAdvancementWeight.of(0, 1).isGreaterThan(ZERO_ADVANCEMENT_WEIGHT));
        assertTrue(TipsetAdvancementWeight.of(1, 1).isGreaterThan(TipsetAdvancementWeight.of(0, 1)));
        assertTrue(TipsetAdvancementWeight.of(1, 1).isGreaterThan(TipsetAdvancementWeight.of(1, 0)));
        assertTrue(TipsetAdvancementWeight.of(1, 1).isGreaterThan(TipsetAdvancementWeight.of(0, 0)));
        assertFalse(TipsetAdvancementWeight.of(0, 1).isGreaterThan(TipsetAdvancementWeight.of(1, 1)));
        assertFalse(TipsetAdvancementWeight.of(1, 0).isGreaterThan(TipsetAdvancementWeight.of(1, 1)));
        assertFalse(TipsetAdvancementWeight.of(0, 0).isGreaterThan(TipsetAdvancementWeight.of(1, 1)));
    }

    @Test
    @DisplayName("isNonzero() Test")
    void isNonzeroTest() {
        assertFalse(ZERO_ADVANCEMENT_WEIGHT.isNonZero());
        assertTrue(TipsetAdvancementWeight.of(1, 0).isNonZero());
        assertTrue(TipsetAdvancementWeight.of(0, 1).isNonZero());
        assertTrue(TipsetAdvancementWeight.of(1, 1).isNonZero());
    }
}
