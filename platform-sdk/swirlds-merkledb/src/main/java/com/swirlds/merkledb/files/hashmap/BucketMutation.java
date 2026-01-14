// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.merkledb.files.hashmap.HalfDiskHashMap.INVALID_VALUE;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A single key-to-path mutation. During flush, all mutations for a single bucket are
 * stored in a list and then applied to the bucket.
 *
 * @param keyBytes a key
 * @param keyHashCode key's hash code
 * @param oldValue an old value (path). If it's HDHM.INVALID_VALUE, it means the old
 *                 value should be ignored
 * @param value a new value (path)
 */
record BucketMutation(@NonNull Bytes keyBytes, int keyHashCode, long oldValue, long value) {

    BucketMutation(final Bytes keyBytes, final int keyHashCode, final long value) {
        this(keyBytes, keyHashCode, INVALID_VALUE, value);
    }
}
