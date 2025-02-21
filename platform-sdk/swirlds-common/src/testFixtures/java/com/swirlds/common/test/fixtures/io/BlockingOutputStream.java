// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.io;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

/**
 * A stream wrapper that can be configured to block.
 */
public class BlockingOutputStream extends OutputStream {

    private final OutputStream baseStream;
    boolean block;

    private final CountDownLatch latch = new CountDownLatch(1);

    public BlockingOutputStream(final OutputStream baseStream) {
        this.baseStream = baseStream;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final int b) throws IOException {
        if (block) {
            try {
                latch.await();
            } catch (final InterruptedException e) {
                throw new IOException(e);
            }
        }

        baseStream.write(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        if (block) {
            try {
                latch.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        baseStream.write(b, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush() throws IOException {
        baseStream.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        latch.countDown();
        baseStream.close();
    }

    /**
     * Once called, this stream will block all writes until it is closed
     */
    public void blockAllWrites() {
        block = true;
    }

    /**
     * Check if the stream has been closed.
     */
    public boolean isClosed() {
        return latch.getCount() == 0;
    }
}
