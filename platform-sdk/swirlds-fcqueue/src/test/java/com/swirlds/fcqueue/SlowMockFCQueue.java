// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import static com.swirlds.common.utility.ByteUtils.byteArrayToLong;
import static com.swirlds.common.utility.ByteUtils.longToByteArray;
import static com.swirlds.fcqueue.internal.FCQHashAlgorithm.HASH_RADIX;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import com.swirlds.fcqueue.internal.FCQHashAlgorithm;
import com.swirlds.fcqueue.internal.FCQueueNode;
import com.swirlds.fcqueue.internal.FCQueueNodeIterator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.SerializableHashable;

/**
 * A threadsafe fast-copyable queue, each of whose elements is fast-copyable. Elements must always be inserted at the
 * tail and removed from the head. It is not allowed to insert nulls. This is fast copyable. A fast copy of a queue can
 * be either immutable or mutable. A mutable fast copy can only be created from a mutable queue, which would then become
 * immutable after creating this mutable fast copy.
 *
 * Element insertion/deletion and fast copy creation/deletion all take constant time. Except that if a queue has n
 * elements that are not in any other queue in its queue group, then deleting it takes O(n) time.
 *
 * The FCQueue objects can be thought of as being organized into "queue groups". A fast copy of a queue creates another
 * queue in the same queue group. But instantiating a queue with "new" and the constructor creates a new queue group.
 *
 * All write operations are synchronized within a queue group. So it is possible to write to two different queue
 * groups at the same time, but writing to different queues in the same queue group will be done serially. Reads via
 * getters are also serialized within a thread group, but it is ok for multiple iterators to be running in multiple
 * threads at the same time within that thread group. An iterator for a queue will throw an exception if it is used after
 * a write to that queue, but it is unaffected by writes to other queues in that queue group.
 */
public class SlowMockFCQueue<E extends FastCopyable & SerializableHashable> extends FCQueue<E> {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private static final long CLASS_ID = 0x69c284363f531bccL;

    static final Supplier<FCQueue<FCInt>> slowSupplier = SlowMockFCQueue::new;

    /**
     * Calculate hash as: sum hash, rolling hash, Merkle hash.
     * rolling hash is recommended for now (unless Merkle is tried and found fast enough)
     */
    protected static final FCQHashAlgorithm HASH_ALGORITHM = FCQHashAlgorithm.ROLLING_HASH;

    /** The digest type used by FCQ */
    private static final DigestType digestType = DigestType.SHA_384;

    /** The default null hash, all zeros */
    public static final byte[] NULL_HASH = new byte[digestType.digestLength()];

    /** the number of elements in this queue */
    protected int size;

    /** the head of this queue */
    protected FCQueueNode<E> head;

    /** the tail of this queue */
    protected FCQueueNode<E> tail;

    /**
     * The number of times this queue has changed so far, such as by add/remove/clear. This could be made volatile to
     * catch more bugs, but then operations would be slower.
     */
    protected int numChanges;

    /** 3 to the power of size, modulo 2 to the 64. Used for the rolling hash */
    protected long threeToSize;

    /** the original FCQueue created with "new". Shared by the whole queue group, so every method synchronizes on it */
    protected final SlowMockFCQueue<E> original;

    /** the hash of set of elements in the queue. */
    protected byte[] hash = getNullHash();

    /**
     * Instantiates a new empty queue which doesn't require deserialization
     */
    public SlowMockFCQueue() {
        size = 0;
        // 3^^0 mod 2^^64 == 1
        threeToSize = 1;
        head = null;
        tail = null;
        original = this;
        // the first in a queue group is mutable until copy(true) is called on it
        setImmutable(false);
    }

    /** Instantiate a queue with all the given parameters. This is just a helper function, not visible to users. */
    protected SlowMockFCQueue(final SlowMockFCQueue<E> fcQueue) {
        this.size = fcQueue.size;
        this.threeToSize = fcQueue.threeToSize;
        System.arraycopy(fcQueue.hash, 0, this.hash, 0, this.hash.length);
        this.head = fcQueue.head;
        this.tail = fcQueue.tail;
        this.original = fcQueue.original;
        this.setImmutable(false);
    }

    /** @return the number of times this queue has changed since it was instantiated by {@code new} or {@code copy} */
    public int getNumChanges() {
        return numChanges;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        byte[] localHash = getNullHash();
        if (head == null) {
            return new Hash(localHash);
        }
        synchronized (this) {
            if (!Arrays.equals(hash, getNullHash())) {
                return new Hash(hash);
            }
        }
        // traverse FCQueueNode from head to tail - works, caches HotH
        for (Iterator<FCQueueNode<E>> it = nodeIterator(); it.hasNext(); ) {
            final FCQueueNode<E> node = it.next();
            final byte[] elementHash;
            if (node.getElementHashOfHash() != null) {
                elementHash = node.getElementHashOfHash();
            } else {
                elementHash = getHash(node.getElement());
                node.setElementHashOfHash(elementHash);
            }
            if (HASH_ALGORITHM == FCQHashAlgorithm.SUM_HASH) {
                // the sum of the hashes of the elements, modulo 2^384.
                int carry = 0;
                for (int i = 0; i < localHash.length; i++) {
                    carry += (localHash[i] & 0xff) + (elementHash[i] & 0xff);
                    localHash[i] = (byte) carry;
                    carry >>= 8;
                }
            } else if (HASH_ALGORITHM == FCQHashAlgorithm.ROLLING_HASH) {
                //   hash64({a,b,c,d}) = a * 3^^3 + b * 3^^2 + c * 3^^1 + d * 3^^0 mod 2^^64
                //	 but we're lazy and skipped the carry - also means this could be done as a vector[6]
                for (int i = 0; i < 48; i += 8) { // process 8 bytes at a time
                    long old = byteArrayToLong(localHash, i);
                    long elm = byteArrayToLong(elementHash, i);
                    longToByteArray(old * HASH_RADIX + elm, localHash, i);
                }
            }
        }
        synchronized (this) {
            hash = localHash;
        }

        return new Hash(hash);
    }

    /**
     * This method is intentionally a no-op.
     *
     * {@inheritDoc}
     */
    @Override
    public void invalidateHash() {
        synchronized (original) {
            this.hash = getNullHash();
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following implement Queue<E>
    //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Inserts the specified element into this queue if it is possible to do so
     * immediately without violating capacity restrictions, returning
     * {@code true} upon success and throwing an {@code IllegalStateException}
     * if no space is currently available.
     *
     * @param o
     * 		the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws IllegalStateException
     * 		if the element cannot be added at this
     * 		time due to capacity restrictions (this cannot happen)
     * @throws ClassCastException
     * 		if the class of the specified element
     * 		prevents it from being added to this queue
     * @throws NullPointerException
     * 		if the specified element is null and
     * 		this queue does not permit null elements
     * @throws IllegalArgumentException
     * 		if some property of this element prevents it from being added to this queue.
     * 		This will happen if the fast-copyable object o
     * 		has an IOException while serializing to create its hash.
     */
    @Override
    public boolean add(final E o) {
        synchronized (original) {
            if (isImmutable()) {
                throw new IllegalStateException("tried to modify an immutable FCQueue");
            }

            if (o == null) {
                throw new NullPointerException("tried to add a null element into an FCQueue");
            }

            if (this.size() >= MAX_ELEMENTS) {
                throw new IllegalStateException(String.format(
                        "tried to add an element to an FCQueue whose size has reached MAX_ELEMENTS: %d", MAX_ELEMENTS));
            }

            invalidateHash();

            final FCQueueNode<E> node;

            if (tail == null) { // current list is empty
                node = new FCQueueNode<>(o);
                head = node;
            } else { // current list is nonempty, so add to the tail
                node = this.tail.insertAtTail(o);
                node.setTowardHead(tail);
                node.setTowardTail(null);
                tail.setTowardTail(node);
                tail.decRefCount();
            }
            tail = node;

            size++;
            numChanges++;
        }
        return true;
    }

    /**
     * Retrieves and removes the head of this queue.  This method differs
     * from {@link #poll() poll()} only in that it throws an exception if
     * this queue is empty.
     *
     * @return the head of this queue
     * @throws NoSuchElementException
     * 		if this queue is empty
     */
    @Override
    public E remove() {
        synchronized (original) {
            final FCQueueNode<E> oldHead = head;

            if (isImmutable()) {
                throw new IllegalArgumentException("tried to remove from an immutable FCQueue");
            }

            if (size == 0 || head == null) {
                throw new NoSuchElementException("tried to remove from an empty FCQueue");
            }

            invalidateHash();

            // Retrieve the element and change the head pointer
            final E element = head.getElement();
            head = head.getTowardTail();

            if (head == null) { // if just removed the last one, then tail should be null, too
                tail.decRefCount();
                tail = null;
            } else {
                head.incRefCount();
            }

            oldHead.decRefCount(); // this will garbage collect the old head, if no copies point to it
            size--;
            numChanges++;

            return element;
        }
    }

    /**
     * Retrieves and removes the head of this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    @Override
    public E poll() {
        synchronized (original) {
            if (this.head == null) {
                return null;
            }

            return remove();
        }
    }

    /**
     * Retrieves, but does not remove, the head of this queue.  This method
     * differs from {@link #peek peek} only in that it throws an exception
     * if this queue is empty.
     *
     * @return the head of this queue
     * @throws NoSuchElementException
     * 		if this queue is empty
     */
    @Override
    public E element() {
        synchronized (original) {
            if (this.head == null) {
                throw new NoSuchElementException("tried to get the head of an empty FCQueue");
            }

            return head.getElement();
        }
    }

    /**
     * Retrieves, but does not remove, the head of this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    @Override
    public E peek() {
        synchronized (original) {
            if (this.head == null) {
                return null;
            }

            return head.getElement();
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following implement FastCopyable
    //////////////////////////////////////////////////////////////////////////////////////////////////

    /** {@inheritDoc} */
    @Override
    public SlowMockFCQueue<E> copy() {
        synchronized (original) {
            if (isImmutable()) {
                throw new IllegalStateException("Tried to make a copy of an immutable FCQueue");
            }

            final SlowMockFCQueue<E> queue = new SlowMockFCQueue<>(this);

            // there can be only one mutable per queue group. If the copy is, then this isn't.
            setImmutable(true);

            if (head != null) {
                head.incRefCount();
            }

            if (tail != null) {
                tail.incRefCount();
            }

            return queue;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following implement Java.util.Collection
    //////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns the number of elements in this collection.
     *
     * @return the number of elements in this collection
     */
    @Override
    public int size() {
        synchronized (original) {
            return size;
        }
    }

    /**
     * Returns {@code true} if this collection contains no elements.
     *
     * @return {@code true} if this collection contains no elements
     */
    @Override
    public boolean isEmpty() {
        synchronized (original) {
            return size == 0;
        }
    }

    /**
     * Returns an iterator over the internal nodes in this queue, in insertion order (head first, tail last).
     *
     * @return an {@code Iterator} over the elements in this collection
     */
    protected Iterator<FCQueueNode<E>> nodeIterator() {
        synchronized (original) {
            return new FCQueueNodeIterator<>(this, head, tail);
        }
    }

    @Override
    public synchronized Iterator<E> iterator() {
        return new FCQueueIterator<>(this, head, tail);
    }

    /**
     * Removes all of the elements from this queue.
     * The queue will be empty and the hash reset to the null value after this method returns.
     * This does not delete the FCQueue object. It just empties the queue.
     */
    @Override
    public void clear() {
        synchronized (original) {
            numChanges++;

            if (head != null) {
                head.decRefCount();
                head = null;
            }

            if (tail != null) {
                tail.decRefCount();
                tail = null;
            }

            size = 0;
            threeToSize = 1; // 3^^0 mod 2^^64 == 1
            resetHash();
        }
    }

    /**
     * Find the hash of a FastCopyable object.
     *
     * @param element
     * 		an element contained by this collection that is being added, deleted, or replaced
     * @return the 48-byte hash of the element (getNullHash() if element is null)
     */
    protected byte[] getHash(final E element) {
        // Handle cases where list methods return null if the list is empty
        if (element == null) {
            return getNullHash();
        }
        // return a hash of a hash, in order to make state proofs smaller in the future
        CRYPTOGRAPHY.digestSync(element);
        return CRYPTOGRAPHY.digestSync(element.getHash()).copyToByteArray();
    }

    protected static byte[] getNullHash() {
        return Arrays.copyOf(NULL_HASH, NULL_HASH.length);
    }

    private void resetHash() {
        System.arraycopy(NULL_HASH, 0, this.hash, 0, this.hash.length);
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }
}
