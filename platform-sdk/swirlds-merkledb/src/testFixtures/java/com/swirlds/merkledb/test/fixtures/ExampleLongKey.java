// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import org.hiero.base.constructable.ConstructableIgnored;

@ConstructableIgnored
public class ExampleLongKey {

    public static Bytes longToKey(final long k) {
        final byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(k);
        return Bytes.wrap(bytes);
    }

    public static long keyToLong(final Bytes key) {
        return key.getLong(0);
    }

    private ExampleLongKey() {}
}
