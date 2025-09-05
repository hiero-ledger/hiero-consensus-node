// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 */
@Deprecated
public class BackedOnDiskIterator<K, V> implements Iterator<K> {

    protected final Codec<K> keyCodec;
    private final MerkleIterator<MerkleNode> itr;
    private K next = null;

    public BackedOnDiskIterator(@NonNull final VirtualMap virtualMap, @NonNull final Codec<K> keyCodec) {
        itr = requireNonNull(virtualMap).treeIterator();
        this.keyCodec = requireNonNull(keyCodec);
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
                try {
                    this.next = keyCodec.parse(k);
                    return true;
                } catch (final ParseException e) {
                    throw new RuntimeException("Failed to parse a key", e);
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
}
