// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.dummy;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An output stream that can be caused to block.
 */
public class BlockingOutputStream extends OutputStream {

    private final AtomicBoolean locked;
    private final OutputStream out;

    /**
     * Create a new blocking stream by wrapping another stream.
     *
     * @param out
     * 		the stream to wrap
     */
    public BlockingOutputStream(final OutputStream out) {
        locked = new AtomicBoolean(false);
        this.out = out;
    }

    /**
     * Lock the stream. No bytes will be accepted, causing write calls to block.
     */
    public void lock() {
        locked.set(true);
    }

    /**
     * Unlock the stream. Write calls will not block.
     */
    public void unlock() {
        locked.set(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) throws IOException {
        while (locked.get()) {
            Thread.onSpinWait();
        }
        out.write(b);
    }
}
