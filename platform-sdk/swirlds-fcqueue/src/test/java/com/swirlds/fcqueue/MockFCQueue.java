// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import static com.swirlds.fcqueue.internal.FCQHashAlgorithm.HASH_RADIX;
import static org.hiero.consensus.utility.ByteUtils.byteArrayToLong;
import static org.hiero.consensus.utility.ByteUtils.longToByteArray;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.test.fixtures.fcqueue.FCInt;
import com.swirlds.fcqueue.internal.FCQHashAlgorithm;
import com.swirlds.fcqueue.internal.FCQueueNode;
import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.SerializableHashable;

/**
 * A thread-safe fast-copyable queue, each of whose elements is fast-copyable. Elements must always be inserted at the
 * tail and removed from the head. It is not allowed to insert nulls. This is fast copyable. A fast copy of a queue can
 * be either immutable or mutable. A mutable fast copy can only be created from a mutable queue, which would then become
 * immutable after creating this mutable fast copy.
 *
 * Element insertion/deletion and fast copy creation/deletion all take constant time. Except that if a queue has n
 * elements that are not in any other queue in its queue group, then deleting it takes O(n) time.
 *
 * The MockFCQueue objects can be thought of as being organized into "queue groups". A fast copy of a queue creates
 * another
 * queue in the same queue group. But instantiating a queue with "new" and the constructor creates a new queue group.
 *
 * All write operations are synchronized within a queue group. So it is possible to write to two different queue
 * groups at the same time, but writing to different queues in the same queue group will be done serially. Reads via
 * getters are also serialized within a thread group, but it is ok for multiple iterators to be running in multiple
 * threads at the same time within that thread group. An iterator for a queue will throw an exception if it is used
 * after
 * a write to that queue, but it is unaffected by writes to other queues in that queue group.
 */
public class MockFCQueue<E extends FastCopyable & SerializableHashable> extends SlowMockFCQueue<E> {

    /** Object identifier of this class (random int). Do NOT change when the class changes its code/name/version. */
    public static final long CLASS_ID = 141236109103L;

    /** the multiplicative inverse of 3 modulo 2 to the 64, in hex, is 15 "a" digits then a "b" digit */
    private static final long INVERSE_3 = 0xaaaaaaaaaaaaaaabL;

    static final Supplier<FCQueue<FCInt>> mockSupplier = MockFCQueue::new;

    private final FCQueue<E> original;

    /** 3 to the power of size, modulo 2 to the 64. Used for the rolling hash */
    private long threeToSize;

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * Instantiate a new empty queue which doesn't require deserialization
     */
    public MockFCQueue() {
        super();
        this.original = this;
        this.threeToSize = 1;
    }

    /** Instantiate a queue with all the given parameters. This is just a helper function, not visible to users. */
    private MockFCQueue(final MockFCQueue<E> mfcQueue) {
        super(mfcQueue);
        this.original = mfcQueue.original;
        this.threeToSize = mfcQueue.threeToSize;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        synchronized (original) {
            return new Hash(Arrays.copyOf(hash, hash.length));
        } // sync
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
            threeToSize *= 3;
            numChanges++;

            final byte[] elementHash = getHash(o);

            // Store the hash of the hash with the FCQueueNode
            node.setElementHashOfHash(elementHash);

            if (HASH_ALGORITHM == FCQHashAlgorithm.SUM_HASH) {
                // This is treated as a "set", not "list", so changing the order does not change the hash. The
                // hash is
                // the sum of the hashes of the elements, modulo 2^384.
                //
                // Note, for applications like Hedera, for the queue of receipts or queue of records, each
                // element of
                // the queue will have a unique  timestamp, and they will always be sorted by those timestamps. So
                // the
                // hash of the set is equivalent to the hash of a list. But if it is ever required to have a hash
                // of a
                // list, then the rolling hash is better (HASH_ALGORITHM 1).

                // perform hash = (hash + elementHash) mod 2^^384
                int carry = 0;
                for (int i = 0; i < hash.length; i++) {
                    carry += (hash[i] & 0xff) + (elementHash[i] & 0xff);
                    hash[i] = (byte) carry;
                    carry >>= 8;
                }
            } else if (HASH_ALGORITHM == FCQHashAlgorithm.ROLLING_HASH) {
                // This is a rolling hash, so it takes into account the order.
                // if the queue contains {a,b,c,d}, where "a" is the head and "d" is the tail, then define:
                //
                //    hash64({a,b,c,d}) = a * 3^^3 + b * 3^^2 + c * 3^^1 + d * 3^^0 mod 2^^64
                //    hash64({a,b,c})   = a * 3^^2 + b * 3^^1 + c * 3^^0            mod 2^^64
                //    hash64({b,c,d})   = b * 3^^2 + c * 3^^1 + d * 3^^0            mod 2^^64
                //
                //    Which implies these:
                //
                //    hash64({a,b,c,d}) = hash64({a,b,c}) * 3 + d                   mod 2^^64     //add(d)
                //    hash64({b,c,d})   = hash64({a,b,c,d}) - a * 3^^3              mod 2^^64     //remove()
                //    deletes a
                //
                // so we add an element by multiplying by 3 and adding the new element's hash,
                // and we remove an element by subtracting that element times 3 to the power of the resulting size.
                //
                // This is all easy to do for a 64-bit hash by keeping track of 3^^size modulo 2^^64, and
                // multiplying
                // it by 3 every time the size increments, and multiplying by the inverse of 3 each time it
                // decrements.
                // The multiplicative inverse of 3 modulo 2^^64 is 0xaaaaaaaaaaaaaaab (that's 15 a digits then a b).
                //
                // It would be much slower to use modulo 2^^384, but we don't have to do that. We can treat the
                // 48-byte hash as a sequence of 6 numbers, each of which is an unsigned 64 bit integer.  We do this
                // rolling hash on each of the 6 numbers independently. Then it ends up being simple and fast

                for (int i = 0; i < hash.length; i += 8) { // process 8 bytes at a time
                    final long old = byteArrayToLong(hash, i);
                    final long elm = byteArrayToLong(elementHash, i);
                    final long value = old * HASH_RADIX + elm;
                    longToByteArray(value, hash, i);
                }
            } else if (HASH_ALGORITHM == FCQHashAlgorithm.MERKLE_HASH) {
                throw new UnsupportedOperationException("Hash algorithm " + HASH_ALGORITHM + " is not supported");
            } else { // invalid hashAlg choice
                throw new UnsupportedOperationException("Hash algorithm " + HASH_ALGORITHM + " is not supported");
            }

            return true;
        }
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

            // Retrieve the previously computed hash of the element's hash
            final byte[] elementHash = head.getElementHashOfHash();

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
            threeToSize *= INVERSE_3;
            numChanges++;

            if (HASH_ALGORITHM == FCQHashAlgorithm.SUM_HASH) {
                // do hash = (hash - elementHash) mod 2^^384

                int carry = 0;
                for (int i = 0; i < hash.length; i++) {
                    carry += (hash[i] & 0xff) - (elementHash[i] & 0xff);
                    hash[i] = (byte) carry;
                    carry >>= 8;
                }
            } else if (HASH_ALGORITHM == FCQHashAlgorithm.ROLLING_HASH) {
                // see comments in add() about the rolling hash

                for (int i = 0; i < 48; i += 8) { // process 8 bytes at a time
                    long old = byteArrayToLong(hash, i);
                    long elm = byteArrayToLong(elementHash, i);
                    longToByteArray(old - elm * threeToSize, hash, i);
                }
            } else if (HASH_ALGORITHM == FCQHashAlgorithm.MERKLE_HASH) {
                throw new UnsupportedOperationException("Hash algorithm " + HASH_ALGORITHM + " is not supported");
            } else { // invalid hashAlg choice
                throw new UnsupportedOperationException("Hash algorithm " + HASH_ALGORITHM + " is not supported");
            }

            return element;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following implement FastCopyable
    //////////////////////////////////////////////////////////////////////////////////////////////////

    /** {@inheritDoc} */
    @Override
    public MockFCQueue<E> copy() {
        synchronized (original) {
            if (isImmutable()) {
                throw new IllegalStateException("Tried to make a copy of an immutable FCQueue");
            }

            final MockFCQueue<E> queue = new MockFCQueue<>(this);

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

    @Override
    public void clear() {
        synchronized (original) {
            super.clear();
            this.threeToSize = 1;
        }
    }
}
