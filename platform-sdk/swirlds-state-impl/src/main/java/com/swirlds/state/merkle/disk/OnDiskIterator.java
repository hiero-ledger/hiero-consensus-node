// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.virtualmap.internal.merkle.VirtualMapState.VM_STATE_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.NoSuchElementException;

public class OnDiskIterator<K, V> extends BackedOnDiskIterator<K, V> {

    private final Bytes stateId;
    private final MerkleIterator<MerkleNode> itr;
    private K next = null;

    public OnDiskIterator(
            @NonNull final VirtualMap virtualMap, @NonNull final Codec<K> keyCodec, @NonNull final Bytes stateId) {
        super(virtualMap, keyCodec);
        this.stateId = requireNonNull(stateId);
        itr = requireNonNull(virtualMap).treeIterator();
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        while (itr.hasNext()) {
            final var merkleNode = itr.next();
            if (merkleNode instanceof VirtualLeafNode leaf) {
                final var k = leaf.getKey();
                // VirtualMap metadata should not be considered as a possible result of the iterator
                if (k.equals(VM_STATE_KEY)) {
                    continue;
                }
                if (checkKey(k)) {
                    try {
                        this.next = keyCodec.parse(k.getBytes(2, k.length() - 2));
                        return true;
                    } catch (final ParseException e) {
                        throw new RuntimeException("Failed to parse a key", e);
                    }
                }
            }
        }
        return false;
    }

    @Override
    public K next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        final var k = next;
        next = null;
        return k;
    }

    /**
     * Checks if the state ID extracted from the provided {@link Bytes} key matches the current iterator's state ID.
     *
     * @param key the {@link Bytes} object from which the state ID will be extracted
     * @return {@code true} if the extracted state ID matches the iterator's state ID, {@code false} otherwise
     */
    private boolean checkKey(final Bytes key) {
        final Bytes stateIdFromKey = key.getBytes(0, 2);
        return stateIdFromKey.equals(this.stateId);
    }
}
