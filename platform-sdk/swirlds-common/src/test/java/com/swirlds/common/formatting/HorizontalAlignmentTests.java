// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_CENTER;
import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_LEFT;
import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_RIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HorizontalAlignment Tests")
public class HorizontalAlignmentTests {

    @Test
    @DisplayName("ALIGNED_LEFT Test")
    void alignedLeftTest() {
        assertEquals("", ALIGNED_LEFT.pad("", ' ', 0, true));
        assertEquals("   ", ALIGNED_LEFT.pad("  ", ' ', 3, true));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 0, true));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 2, true));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 3, true));
        assertEquals("abc ", ALIGNED_LEFT.pad("abc", ' ', 4, true));
        assertEquals("abc       ", ALIGNED_LEFT.pad("abc", ' ', 10, true));
        assertEquals("foo-------", ALIGNED_LEFT.pad("foo", '-', 10, true));

        assertEquals("", ALIGNED_LEFT.pad("", ' ', 0, false));
        assertEquals("  ", ALIGNED_LEFT.pad("  ", ' ', 3, false));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 0, false));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 2, false));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 3, false));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 4, false));
        assertEquals("abc", ALIGNED_LEFT.pad("abc", ' ', 10, false));
        assertEquals("foo", ALIGNED_LEFT.pad("foo", '-', 10, false));
    }

    @Test
    @DisplayName("ALIGNED_RIGHT Test")
    void alignedRightTest() {
        assertEquals("", ALIGNED_RIGHT.pad("", ' ', 0, true));
        assertEquals("   ", ALIGNED_RIGHT.pad("  ", ' ', 3, true));
        assertEquals("abc", ALIGNED_RIGHT.pad("abc", ' ', 0, true));
        assertEquals("abc", ALIGNED_RIGHT.pad("abc", ' ', 2, true));
        assertEquals("abc", ALIGNED_RIGHT.pad("abc", ' ', 3, true));
        assertEquals(" abc", ALIGNED_RIGHT.pad("abc", ' ', 4, true));
        assertEquals("       abc", ALIGNED_RIGHT.pad("abc", ' ', 10, true));
        assertEquals("-------foo", ALIGNED_RIGHT.pad("foo", '-', 10, true));

        assertEquals("", ALIGNED_RIGHT.pad("", ' ', 0, false));
        assertEquals("   ", ALIGNED_RIGHT.pad("  ", ' ', 3, false));
        assertEquals("abc", ALIGNED_RIGHT.pad("abc", ' ', 0, false));
        assertEquals("abc", ALIGNED_RIGHT.pad("abc", ' ', 2, false));
        assertEquals("abc", ALIGNED_RIGHT.pad("abc", ' ', 3, false));
        assertEquals(" abc", ALIGNED_RIGHT.pad("abc", ' ', 4, false));
        assertEquals("       abc", ALIGNED_RIGHT.pad("abc", ' ', 10, false));
        assertEquals("-------foo", ALIGNED_RIGHT.pad("foo", '-', 10, false));
    }

    @Test
    @DisplayName("ALIGNED_CENTER Test")
    void alignedCenterTest() {
        assertEquals("", ALIGNED_CENTER.pad("", ' ', 0, true));
        assertEquals("   ", ALIGNED_CENTER.pad("  ", ' ', 3, true));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 0, true));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 2, true));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 3, true));
        assertEquals("abc ", ALIGNED_CENTER.pad("abc", ' ', 4, true));
        assertEquals(" abc ", ALIGNED_CENTER.pad("abc", ' ', 5, true));
        assertEquals("   abc    ", ALIGNED_CENTER.pad("abc", ' ', 10, true));
        assertEquals("    abc    ", ALIGNED_CENTER.pad("abc", ' ', 11, true));
        assertEquals("---foo----", ALIGNED_CENTER.pad("foo", '-', 10, true));
        assertEquals("----foo----", ALIGNED_CENTER.pad("foo", '-', 11, true));

        assertEquals("", ALIGNED_CENTER.pad("", ' ', 0, false));
        assertEquals("  ", ALIGNED_CENTER.pad("  ", ' ', 3, false));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 0, false));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 2, false));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 3, false));
        assertEquals("abc", ALIGNED_CENTER.pad("abc", ' ', 4, false));
        assertEquals(" abc", ALIGNED_CENTER.pad("abc", ' ', 5, false));
        assertEquals("   abc", ALIGNED_CENTER.pad("abc", ' ', 10, false));
        assertEquals("    abc", ALIGNED_CENTER.pad("abc", ' ', 11, false));
        assertEquals("---foo", ALIGNED_CENTER.pad("foo", '-', 10, false));
        assertEquals("----foo", ALIGNED_CENTER.pad("foo", '-', 11, false));
    }
}
