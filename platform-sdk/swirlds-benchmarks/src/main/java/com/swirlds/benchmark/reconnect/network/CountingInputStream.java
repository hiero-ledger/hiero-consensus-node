// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class CountingInputStream extends FilterInputStream {

    private volatile long count;

    CountingInputStream(final InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        final int b = in.read();
        if (b >= 0) {
            count++;
        }
        return b;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        final int n = in.read(b, off, len);
        if (n > 0) {
            count += n;
        }
        return n;
    }

    long count() {
        return count;
    }
}
