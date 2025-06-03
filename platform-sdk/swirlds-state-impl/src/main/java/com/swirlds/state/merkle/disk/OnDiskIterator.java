// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.hedera.pbj.runtime.ProtoParserTools.readNextFieldNumber;
import static com.swirlds.virtualmap.internal.merkle.VirtualMapState.VM_STATE_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.VirtualMapKey;
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

    private final int fieldNumber;
    private final MerkleIterator<MerkleNode> itr;
    private K next = null;

    public OnDiskIterator(
            @NonNull final VirtualMap virtualMap, @NonNull final Codec<K> keyCodec, final int fieldNumber) {
        super(virtualMap, keyCodec);
        this.fieldNumber = fieldNumber;
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
                final Bytes k = leaf.getKey();
                // VirtualMap metadata should not be considered as a possible result of the iterator
                if (k.equals(VM_STATE_KEY)) {
                    continue;
                }
                int nextFieldNumber = readNextFieldNumber(k.toReadableSequentialData());
                if (fieldNumber == nextFieldNumber) {
                    try {
                        VirtualMapKey parse = VirtualMapKey.PROTOBUF.parse(k);
                        this.next = parse.key().as();
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
