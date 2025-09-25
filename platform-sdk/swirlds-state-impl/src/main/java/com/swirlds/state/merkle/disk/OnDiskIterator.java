// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.iterators.MerkleIterator;
import com.swirlds.state.merkle.StateKeyUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class OnDiskIterator<K> implements Iterator<K> {

    // State ID
    private final int stateId;

    // Key protobuf codec
    private final Codec<K> keyCodec;

    // Merkle node iterator, typically over a virtual map
    private final MerkleIterator<MerkleNode> itr;

    private K next = null;

    public OnDiskIterator(@NonNull final VirtualMap virtualMap, @NonNull final Codec<K> keyCodec, final int stateId) {
        this.stateId = stateId;
        this.keyCodec = requireNonNull(keyCodec);
        itr = requireNonNull(virtualMap).treeIterator();
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        while (itr.hasNext()) {
            final MerkleNode merkleNode = itr.next();
            if (merkleNode instanceof VirtualLeafNode leaf) {
                final Bytes stateKey = leaf.getKey();
                final int nextNextStateId = StateKeyUtils.extractStateIdFromStateKeyOneOf(stateKey);
                if (stateId == nextNextStateId) {
                    try {
                        this.next = StateKeyUtils.extractKeyFromStateKeyOneOf(stateKey, keyCodec);
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
}
