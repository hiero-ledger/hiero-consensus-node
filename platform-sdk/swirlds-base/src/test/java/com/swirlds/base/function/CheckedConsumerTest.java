// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckedConsumerTest {

    @Test
    void testOf() {
        // given
        final List<String> list = new ArrayList<>();
        final CheckedConsumer<String, RuntimeException> consumer = CheckedConsumer.of(s -> list.add(s));

        // when
        consumer.accept("test");

        // then
        assertEquals(1, list.size());
        assertTrue(list.contains("test"));
    }

    @Test
    void testOfNull() {
        assertThrows(NullPointerException.class, () -> CheckedConsumer.of(null));
    }
}
