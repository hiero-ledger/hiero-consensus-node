// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class CountingOutputStream extends FilterOutputStream {

    private volatile long count;

    CountingOutputStream(final OutputStream out) {
        super(out);
    }

    @Override
    public void write(final int b) throws IOException {
        out.write(b);
        count++;
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        out.write(b, off, len);
        count += len;
    }

    long count() {
        return count;
    }
}
