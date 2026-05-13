// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;

public final class TestKey {

    public static Bytes longToKey(final long k) {
        final byte[] bytes = new byte[Long.BYTES];
        ByteBuffer.wrap(bytes).putLong(k);
        return Bytes.wrap(bytes);
    }
}
