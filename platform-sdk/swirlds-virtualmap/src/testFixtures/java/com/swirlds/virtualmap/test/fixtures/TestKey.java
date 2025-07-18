// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;

public final class TestKey {

    public static Bytes longToKey(final long k) {
        final byte[] bytes = new byte[Long.BYTES];
        // sub-optimal, but easy
        ByteBuffer.wrap(bytes).putLong(k);
        return Bytes.wrap(bytes);
    }

    public static Bytes charToKey(final char c) {
        return longToKey(c);
    }
}
