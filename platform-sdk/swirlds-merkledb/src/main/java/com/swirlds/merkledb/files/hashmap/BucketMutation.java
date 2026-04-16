// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.internal.Path;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A single leaf-to-path mutation. During flush, all mutations for a single bucket are
 * stored in a list and then applied to the bucket.
 *
 * @param key a key
 * @param keyHashCode key's hash code
 * @param oldPath an old path. If it's {@link Path#INVALID_PATH}, it means the old
 *             path should be ignored
 * @param path a new path. If it's {@link Path#INVALID_PATH}, it means the leaf
 *             should be deleted
 * @param value a value. May be null, only if the path is {@link Path#INVALID_PATH}
 */
record BucketMutation(@NonNull Bytes key, int keyHashCode, long oldPath, long path, @Nullable Bytes value) {

    BucketMutation(@NonNull final Bytes key, final int keyHashCode, final long path, @Nullable final Bytes value) {
        this(key, keyHashCode, Path.INVALID_PATH, path, value);
    }
}
