// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import java.io.InputStream;

public class TestInput extends InputStream {
    private int[] input;
    private int index = 0;

    public void setInput(final int[] input) {
        this.input = input;
        this.index = 0;
    }

    public void reset() {
        input = null;
        index = 0;
    }

    @Override
    public int read() {
        if (input == null) {
            throw new IllegalStateException("No input set");
        }
        if (index >= input.length) {
            return -1; // end of stream
        }
        return input[index++];
    }
}
