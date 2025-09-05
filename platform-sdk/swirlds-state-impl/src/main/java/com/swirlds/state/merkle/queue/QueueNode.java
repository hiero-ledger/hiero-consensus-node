// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import static com.swirlds.state.merkle.logging.StateLogger.logQueueAdd;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueIterate;
import static com.swirlds.state.merkle.logging.StateLogger.logQueuePeek;
import static com.swirlds.state.merkle.logging.StateLogger.logQueueRemove;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.utility.DebugIterationEndpoint;
import com.swirlds.common.utility.Labeled;
import com.swirlds.fcqueue.FCQueue;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.merkle.singleton.ValueLeaf;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;

/**
 * A merkle node with a string (the label) as the left child, and the merkle node value as the right
 * child. We actually support a raw type (any type!) as the value, and we serialize it and put it
 * into a simple merkle node.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 * @param <E> The element type
 */
@Deprecated
@DebugIterationEndpoint
public class QueueNode<E> extends PartialBinaryMerkleInternal implements Labeled, MerkleInternal {

    private static final long CLASS_ID = 0x990FF87AD2691DCL;
    public static final int CLASS_VERSION = 1;

    /** Key codec. */
    private final Codec<E> codec;

    /** Key class id. */
    private final Long queueNodeClassId;

    private final Long leafClassId;

    /**
     * @deprecated Only exists for constructable registry as it works today. Remove ASAP!
     */
    @Deprecated(forRemoval = true)
    public QueueNode() {
        setLeft(new StringLeaf());
        setRight(null);
        this.codec = null;
        this.queueNodeClassId = CLASS_ID;
        this.leafClassId = ValueLeaf.CLASS_ID;
    }

    /**
     * Create a new instance.
     *
     *
     */
    public QueueNode(
            @NonNull String label, final long queueNodeClassId, final long leafClassId, @NonNull final Codec<E> codec) {
        setLeft(new StringLeaf(label));
        setRight(new FCQueue<ValueLeaf<E>>());
        this.codec = requireNonNull(codec);
        this.queueNodeClassId = queueNodeClassId;
        this.leafClassId = leafClassId;
    }

    /** Copy constructor */
    private QueueNode(@NonNull final QueueNode<E> other) {
        this.setLeft(other.getLeft().copy());
        this.setRight(other.getRight().copy());
        this.codec = other.codec;
        this.queueNodeClassId = other.queueNodeClassId;
        this.leafClassId = other.leafClassId;
    }

    @Override
    public QueueNode<E> copy() {
        return new QueueNode<>(this);
    }

    @Override
    public long getClassId() {
        return queueNodeClassId;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    @Override
    public String getLabel() {
        final StringLeaf left = getLeft();
        return left.getLabel();
    }

    /** Adds an element to this queue. */
    public void add(E element) {
        getQueue().add(new ValueLeaf<>(leafClassId, codec, element));
        // Log to transaction state log, what was added
        logQueueAdd(getLabel(), element);
    }

    /** Peek an element */
    public E peek() {
        final var valueLeaf = getQueue().peek();
        // Log to transaction state log, what was peeked
        logQueuePeek(getLabel(), valueLeaf);
        return valueLeaf == null ? null : valueLeaf.getValue();
    }

    /** Retrieve and remove an element */
    public E remove() {
        final var valueLeaf = getQueue().remove();
        // Log to transaction state log, what was added
        logQueueRemove(getLabel(), valueLeaf);
        return valueLeaf == null ? null : valueLeaf.getValue();
    }

    /** Iterate over all elements */
    public Iterator<E> iterator() {
        // Log to transaction state log, what was iterated
        logQueueIterate(getLabel(), getRight());
        final var itr = getQueue().stream().iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return itr.hasNext();
            }

            @Override
            public E next() {
                final var valueLeaf = itr.next();
                return valueLeaf == null ? null : valueLeaf.getValue();
            }
        };
    }

    /** Utility shorthand to get the queue */
    private FCQueue<ValueLeaf<E>> getQueue() {
        return getRight();
    }
}
