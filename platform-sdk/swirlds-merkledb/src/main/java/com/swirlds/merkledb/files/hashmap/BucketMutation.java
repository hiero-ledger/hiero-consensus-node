// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.merkledb.files.hashmap.HalfDiskHashMap.INVALID_VALUE;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Objects;

/**
 * A simple linked-list of "mutations" for a bucket.
 *     The key.
 */
class BucketMutation {

    private final Bytes keyBytes;
    private final int keyHashCode;
    // If old value is HDHM.INVALID_VALUE, it means the old value should not be checked
    private long oldValue;
    private long value;
    private BucketMutation next;

    BucketMutation(final Bytes keyBytes, final int keyHashCode, final long value) {
        this.keyBytes = Objects.requireNonNull(keyBytes);
        this.keyHashCode = keyHashCode;
        this.value = value;
        this.oldValue = INVALID_VALUE;
        this.next = null;
    }

    BucketMutation(final Bytes keyBytes, final int keyHashCode, final long oldValue, final long value) {
        this.keyBytes = Objects.requireNonNull(keyBytes);
        this.keyHashCode = keyHashCode;
        this.value = value;
        this.oldValue = oldValue;
        this.next = null;
    }

    /**
     * To be called on the "head" of the list, updates an existing mutation
     * with the same key or creates a new mutation at the end of the list.
     * @param keyBytes
     * 		The key (cannot be null)
     * @param value
     * 		The value (cannot be null)
     */
    void put(final Bytes keyBytes, final int keyHashCode, final long value) {
        BucketMutation mutation = this;
        while (true) {
            if (mutation.keyBytes.equals(keyBytes)) {
                assert (keyHashCode == 0) || (mutation.keyHashCode == keyHashCode);
                mutation.value = value;
                mutation.oldValue = INVALID_VALUE;
                return;
            } else if (mutation.next != null) {
                mutation = mutation.next;
            } else {
                mutation.next = new BucketMutation(keyBytes, keyHashCode, value);
                return;
            }
        }
    }

    void putIfEqual(final Bytes keyBytes, final int keyHashCode, final long oldValue, final long value) {
        BucketMutation mutation = this;
        while (true) {
            if (mutation.keyBytes.equals(keyBytes)) {
                assert (keyHashCode == 0) || (mutation.keyHashCode == keyHashCode);
                if (mutation.value == oldValue) {
                    mutation.value = value;
                }
                return;
            } else if (mutation.next != null) {
                mutation = mutation.next;
            } else {
                mutation.next = new BucketMutation(keyBytes, keyHashCode, oldValue, value);
                return;
            }
        }
    }

    /**
     * Computes the size of the list from this point to the end of the list.
     * @return
     * 		The number of mutations, including this one, from here to the end.
     */
    int size() {
        int size = 1;
        BucketMutation mutation = next;
        while (mutation != null) {
            size++;
            mutation = mutation.next;
        }
        return size;
    }

    // For testing purposes
    long getValue() {
        return value;
    }

    // For testing purposes
    long getOldValue() {
        return oldValue;
    }

    // For testing purposes
    BucketMutation getNext() {
        return next;
    }

    /**
     * Visit each mutation in the list, starting from this mutation.
     * @param consumer
     * 		The callback. Cannot be null.
     */
    void forEachKeyValue(MutationCallback consumer) {
        BucketMutation mutation = this;
        while (mutation != null) {
            consumer.accept(mutation.keyBytes, mutation.keyHashCode, mutation.oldValue, mutation.value);
            mutation = mutation.next;
        }
    }

    /**
     * A simple callback for {@link BucketMutation#forEachKeyValue(MutationCallback)}.
     */
    interface MutationCallback {
        void accept(final Bytes keyBytes, final int keyHashCode, final long oldValue, final long value);
    }
}
