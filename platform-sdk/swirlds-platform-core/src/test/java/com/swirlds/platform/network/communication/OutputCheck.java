// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import java.io.OutputStream;
import org.junit.jupiter.api.Assertions;

public class OutputCheck extends OutputStream {
    private int[] expected;
    private int index = 0;

    public void setExpected(final int[] expected) {
        this.expected = expected;
        this.index = 0;
    }

    public void reset() {
        expected = null;
        index = 0;
    }

    @Override
    public void write(int b) {
        if (expected == null) {
            throw new IllegalStateException("No expected output set");
        }
        b &= 0xff; // because the contracts of read() and write() are terrible
        Assertions.assertEquals(
                expected[index],
                b,
                String.format("Expected byte %d but got %d at index %d", expected[index], b, index));
        index++;
    }

    @Override
    public void flush() {
        // nothing to do
    }
}
