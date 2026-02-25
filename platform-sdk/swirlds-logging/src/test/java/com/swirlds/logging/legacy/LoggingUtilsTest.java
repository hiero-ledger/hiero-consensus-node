// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

import static com.swirlds.logging.legacy.LoggingUtils.plural;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Logging Utils Test")
class LoggingUtilsTest {

    @Test
    @Tag(TestComponentTags.LOGGING)
    @DisplayName("Test Plural")
    void testPlural() {
        assertEquals("dogs", plural(0, "dog"), "expected plural");
        assertEquals("dog", plural(1, "dog"), "expected singular");
        assertEquals("dogs", plural(2, "dog"), "expected plural");

        assertEquals("geese", plural(0, "goose", "geese"), "expected plural");
        assertEquals("goose", plural(1, "goose", "geese"), "expected singular");
        assertEquals("geese", plural(2, "goose", "geese"), "expected plural");
    }
}
