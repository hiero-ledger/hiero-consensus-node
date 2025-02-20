// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CheckedFunctionTest {

    @Test
    void testOfNull() {
        assertThrows(NullPointerException.class, () -> CheckedFunction.of(null));
    }

    @Test
    void testOfNonNull() {
        // given
        final CheckedFunction<Integer, Integer, Exception> of = CheckedFunction.of((t) -> {
            return t + 1;
        });

        try {
            final Integer apply = of.apply(1);
            assertEquals(2, apply.intValue());
        } catch (Exception e) {
            Assertions.fail("Should not throw exception");
        }
    }
}
