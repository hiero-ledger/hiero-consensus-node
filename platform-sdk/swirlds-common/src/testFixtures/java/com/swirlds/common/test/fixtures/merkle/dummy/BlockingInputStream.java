// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An input stream that can be caused to block.
 */
public class BlockingInputStream extends InputStream {

    private final AtomicBoolean locked;
    private final InputStream in;

    /**
     * Create a new blocking stream by wrapping another stream.
     *
     * @param in
     * 		the stream to wrap
     */
    public BlockingInputStream(final InputStream in) {
        locked = new AtomicBoolean(false);
        this.in = in;
    }

    /**
     * Lock the stream, causing read calls to block.
     */
    public void lock() {
        locked.set(true);
    }

    /**
     * Unlock the stream. Read calls will not block.
     */
    public void unlock() {
        locked.set(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        while (locked.get()) {
            Thread.onSpinWait();
        }
        return in.read();
    }
}
