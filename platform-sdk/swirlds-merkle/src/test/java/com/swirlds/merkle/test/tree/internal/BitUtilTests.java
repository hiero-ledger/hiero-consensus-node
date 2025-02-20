// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.tree.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.merkle.tree.internal.BitUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bit Util Tests")
class BitUtilTests {

    @Test
    @DisplayName("Basic Left-most")
    void basicLeftMost() {
        assertEquals(0, BitUtil.findLeftMostBit(0L), "Left most bit is: 0000...0000");
        assertEquals(1, BitUtil.findLeftMostBit(1L), "Left most bit is: 0000...0001");
        assertEquals(2, BitUtil.findLeftMostBit(2L), "Left most bit is: 0000...0010");
        assertEquals(2, BitUtil.findLeftMostBit(3L), "Left most bit is: 0000...0011");
        assertEquals(4, BitUtil.findLeftMostBit(4L), "Left most bit is: 0000...0100");
        assertEquals(4, BitUtil.findLeftMostBit(5L), "Left most bit is: 0000...0101");
        assertEquals(4, BitUtil.findLeftMostBit(6L), "Left most bit is: 0000...0110");
        assertEquals(4, BitUtil.findLeftMostBit(7L), "Left most bit is: 0000...0111");
        assertEquals(8, BitUtil.findLeftMostBit(8L), "Left most bit is: 0000...1000");
        assertEquals(8, BitUtil.findLeftMostBit(9L), "Left most bit is: 0000...1001");
        assertEquals(8, BitUtil.findLeftMostBit(10L), "Left most bit is: 0000...1010");
        assertEquals(8, BitUtil.findLeftMostBit(11L), "Left most bit is: 0000...1011");
        assertEquals(8, BitUtil.findLeftMostBit(12L), "Left most bit is: 0000...1100");
        assertEquals(8, BitUtil.findLeftMostBit(13L), "Left most bit is: 0000...1101");
        assertEquals(8, BitUtil.findLeftMostBit(14L), "Left most bit is: 0000...1110");
        assertEquals(8, BitUtil.findLeftMostBit(15L), "Left most bit is: 0000...1111");
        assertEquals(16, BitUtil.findLeftMostBit(16L), "Left most bit is: 0000...0001_0000");
    }
}
