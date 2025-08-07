// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;

public final class TestObjectKey {

    public static final int BYTES = Long.BYTES * 2;

    private long k;

    public static Bytes longToKey(final long k) {
        final byte[] bytes = new byte[BYTES];
        // sub-optimal, but easy
        ByteBuffer.wrap(bytes).putLong(k).putLong(k);
        return Bytes.wrap(bytes);
    }

    private TestObjectKey() {}
}
