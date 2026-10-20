// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Test helpers for building deeply nested keys, used to exercise the nested-key depth caps
 * ({@code AttributeValidator.MAX_NESTED_KEY_LEVELS} at handle and {@code hedera.transaction.maxProtoMessageDepth} at
 * ingest).
 */
public final class NestedKeys {
    private NestedKeys() {}

    /** A simple (non-compound) ED25519 key that serves as the leaf of a nested key. */
    public static Key simpleKey() {
        return Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    }

    /** Wraps {@link #simpleKey()} in {@code levels} nested {@link KeyList}s (each level adds one {@code KeyList}). */
    public static Key nestedKeyList(final int levels) {
        var key = simpleKey();
        for (int i = 0; i < levels; i++) {
            key = Key.newBuilder()
                    .keyList(KeyList.newBuilder().keys(key).build())
                    .build();
        }
        return key;
    }

    /** Wraps {@link #simpleKey()} in {@code levels} nested 1-of-1 {@link ThresholdKey}s (each level adds one). */
    public static Key nestedThresholdKey(final int levels) {
        var key = simpleKey();
        for (int i = 0; i < levels; i++) {
            key = Key.newBuilder()
                    .thresholdKey(ThresholdKey.newBuilder()
                            .threshold(1)
                            .keys(KeyList.newBuilder().keys(key).build())
                            .build())
                    .build();
        }
        return key;
    }
}
