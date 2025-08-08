// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import org.hiero.base.constructable.ConstructableIgnored;

@ConstructableIgnored
@SuppressWarnings("unused")
public class ExampleLongLongKey {

    public static Bytes longToKey(final long k) {
        return longToKey(k, Long.MAX_VALUE - k);
    }

    public static Bytes longToKey(final long k1, final long k2) {
        final byte[] bytes = new byte[Long.BYTES * 2];
        ByteBuffer.wrap(bytes).putLong(k1).putLong(k2);
        return Bytes.wrap(bytes);
    }

    private ExampleLongLongKey() {}
}
