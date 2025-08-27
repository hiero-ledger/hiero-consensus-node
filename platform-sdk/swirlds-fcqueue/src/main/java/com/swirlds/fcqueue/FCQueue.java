// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fcqueue;

import static org.hiero.base.utility.ByteUtils.byteArrayToLong;
import static org.hiero.base.utility.ByteUtils.longToByteArray;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SerializableHashable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A threadsafe fast-copyable queue, each of whose elements is fast-copyable. Elements must always be inserted at the
 * tail and removed from the head. It is not allowed to insert nulls. This is fast copyable. A fast copy of a queue is
 * mutable and the original queue becomes immutable. A mutable fast copy can only be created from a mutable queue,
 * which would then become immutable after creating this mutable fast copy, or by using the "new" operator.
 *
 * Element insertion/deletion and fast copy creation/deletion all take constant time.
 *
 * The FCQueue objects can be thought of as being organized into "queue groups". A fast copy of a queue creates another
 * queue in the same queue group. But instantiating a queue with "new" and the constructor creates a new queue group.
 *
 * All write operations are synchronized with the current instance. It is ok for multiple iterators to be running in
 * multiple threads at the same time. An iterator for a mutable queue provides a snapshot view of the queue at the
 * time of iterator creation. A reverse iterator materializes the view (should not be used for huge queues).
 *
 * @deprecated should be removed once the migration to the Mega Map state organization is completed on the mainnet.
 */
@Deprecated
public class FCQueue<E extends FastCopyable & SerializableHashable> extends PartialMerkleLeaf
        implements Queue<E>, MerkleLeaf {
    private static final Cryptography CRYPTOGRAPHY = CryptographyProvider.getInstance();

    private static class ClassVersion {
        /**
         * In this version, serialization was performed by copyTo/copyToExtra and deserialization was performed by
         * copyFrom/copyFromExtra. This version is not supported by later deserialization methods and must be handled
         * specially by the platform.
         */
        public static final int ORIGINAL = 1;
        /**
         * FCQ implements MerkleLeaf, element implements FastCopyable and SerializableHashable
         */
        public static final int MIGRATE_TO_SERIALIZABLE = 2;

        /**
         * FCQ doesn't add a hash to the byte stream because the hash is redundant
         */
        public static final int REMOVED_HASH = 3;
    }

    /** Object identifier of this class (random int). Do NOT change when the class changes its code/name/version. */
    public static final long CLASS_ID = 139236190103L;

    /** Maximum number of elements FCQueue supports */
    public static final int MAX_ELEMENTS = 100_000_000;

    /** The digest type used by FCQ */
    private static final DigestType DIGEST_TYPE = DigestType.SHA_384;

    private static final long HASH_RADIX = 3;

    /** The bytes of a NULL_HASH */
    private static final byte[] NULL_HASH_BYTES = new byte[DIGEST_TYPE.digestLength()];
    /** A hash value representing a null element or a destroyed queue */
    private static final Hash NULL_HASH = new Hash(NULL_HASH_BYTES);

    /** the number of elements in this queue */
    private int size;

    /** the head of this queue, inclusive */
    private Node<E> head;

    /** the tail of this queue, exclusive */
    private Node<E> tail;

    /** the first unhashed node, shared between queues in a group */
    private final AtomicReference<Node<E>> unhashed;

    /** the hash of this queue once it becomes immutable */
    private volatile Hash hash;

    static class Node<E extends FastCopyable> {
        /** the element in the list */
        E element;

        /** the next node in the direction toward the tail, or null if none */
        Node<E> next;

        /** the running hash of all nodes from the origin up to, but excluding, the current node */
        volatile long[] runningHash; // NOSONAR
    }

    /**
     * Instantiates a new empty queue which doesn't require deserialization
     */
    public FCQueue() {
        size = 0;
        head = new Node<>();
        head.runningHash = new long[DIGEST_TYPE.digestLength() / Long.BYTES];
        tail = head;
        unhashed = new AtomicReference<>(head);
        hash = null;
    }

    /** Instantiate a queue with all the given parameters. This is just a helper function, not visible to users. */
    FCQueue(final FCQueue<E> fcQueue) {
        super(fcQueue);
        size = fcQueue.size;
        head = fcQueue.head;
        tail = fcQueue.tail;
        unhashed = fcQueue.unhashed;
        hash = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        if (hash != null) {
            return hash;
        }

        synchronized (this) {
            Hash result = hash;
            if (result == null) {
                result = computeHash();
                if (isImmutable()) {
                    hash = result;
                }
            }
            return result;
        }
    }

    /**
     * This is a rolling hash that takes the order into account.
     * If the queue contains {a,b,c,d}, where "a" is the head and "d" is the tail, then define:
     *
     * <p> hash64({a,b,c,d}) = a * 3^^3 + b * 3^^2 + c * 3^^1 + d * 3^^0 mod 2^^64 </p>
     * <p> hash64({a,b,c})   = a * 3^^2 + b * 3^^1 + c * 3^^0            mod 2^^64 </p>
     * <p> hash64({b,c,d})   = b * 3^^2 + c * 3^^1 + d * 3^^0            mod 2^^64 </p>
     * <p>
     * Which implies these: </p>
     *
     * <p> hash64({a,b,c,d}) = hash64({a,b,c}) * 3 + d                   mod 2^^64     // add(d) </p>
     * <p> hash64({b,c,d})   = hash64({a,b,c,d}) - a * 3^^3              mod 2^^64     // remove() </p>
     * <p> hash64({c,d})     = hash64({a,b,c,d}) - a * 3^^3 - b * 3^^2   mod 2^^64
     *                       = hash64({a,b,c,d}) - hash64({a,b}) * 3^^size mod 2^^64 </p>
     * <p>
     * It would be much slower to use modulo 2^^384, but we don't have to do that. We can treat the
     * 48-byte hash as a sequence of 6 numbers, each of which is an unsigned 64-bit integer.  We do this
     * rolling hash on each of the 6 numbers independently. Then it ends up being simple and fast. </p>
     * <p>
     * To compute hashes of queue copies concurrently and efficiently we maintain a running hash for each node.
     * Node's running hash is a weighted sum as above that covers all nodes from the origin up to, but excluding,
     * the current node. It's invariant between all copies in a queue group. Example:</p>
     * <p> Queue:        {a} -> {b} -> {c}      -> {} </p>
     * <p> RunningHash:  [0]    [a]    [a * 3 + b] [a * 3^2 + b * 3 + c] </p>
     * <p>
     * A queue hash can be computed as: </p>
     * <p><code> tail.runningHash - head.runningHash * HASH_RADIX<sup>size</sup> </code></p>
     * <p>
     * Multiple queues in a group can compute their hashes concurrently without synchronization as all updates in the
     * shared data structure are invariant. Volatile <code>runningHash</code> helps to reduce overlap between
     * threads.</p>
     */
    private Hash computeHash() {
        // Ensure we have tail's running hash
        if (tail.runningHash == null) {
            Node<E> node = unhashed.get();
            while (tail.runningHash == null) {
                final Node<E> next = node.next;
                if (next.runningHash == null) {
                    final byte[] elementHash = getHash(node.element);
                    final long[] runningHash = node.runningHash.clone();
                    for (int i = 0; i < runningHash.length; ++i) {
                        runningHash[i] = runningHash[i] * HASH_RADIX + byteArrayToLong(elementHash, i * Long.BYTES);
                    }
                    next.runningHash = runningHash;
                }
                node = next;
            }
            unhashed.set(node); // it's OK to advance unhashed non-deterministically between multiple threads
        }

        // Compute the queue hash as a weighted difference of running hashes of head and tail
        final long[] headHash = head.runningHash;
        final long[] tailHash = tail.runningHash;
        final long exponent = power(size);
        final byte[] result = new byte[headHash.length * Long.BYTES];
        for (int i = 0; i < headHash.length; ++i) {
            longToByteArray(tailHash[i] - headHash[i] * exponent, result, i * Long.BYTES);
        }
        return new Hash(result);
    }

    /**
     * Computes a long value of <code>HASH_RADIX<sup>exponent</sup></code> ignoring multiplication overflow.
     * @param y
     * 		exponent
     * @return (HASH_RADIX ^ y) mod 2^64
     */
    private static long power(int y) {
        long res = 1;
        long x = HASH_RADIX;
        while (y > 0) {
            if ((y & 1) == 1) {
                res *= x;
            }
            y >>= 1;
            x *= x;
        }
        return res;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void setHash(final Hash hash) {
        throw new UnsupportedOperationException("FCQueue computes its own hash");
    }

    @Override
    public boolean isSelfHashing() {
        return true;
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
     * @param element
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
    public synchronized boolean add(final E element) {
        if (isImmutable()) {
            throw new IllegalStateException("tried to modify an immutable FCQueue");
        }

        if (element == null) {
            throw new NullPointerException("tried to add a null element into an FCQueue");
        }

        if (size() >= MAX_ELEMENTS) {
            throw new IllegalStateException(String.format(
                    "tried to add an element to an FCQueue whose size has reached MAX_ELEMENTS: %d", MAX_ELEMENTS));
        }

        tail.element = element;
        tail.next = new Node<>();
        tail = tail.next;

        size++;

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
    public synchronized E remove() {
        if (isImmutable()) {
            throw new IllegalArgumentException("tried to remove from an immutable FCQueue");
        }

        if (size == 0) {
            throw new NoSuchElementException("tried to remove from an empty FCQueue");
        }

        final E element = head.element;
        head = head.next;

        size--;

        return element;
    }

    /**
     * Inserts the specified element into this queue. This is equivalent to {@code add(o)}.
     *
     * @param o
     * 		the element to add
     * @return {@code true} if the element was added to this queue, else
     *        {@code false}
     * @throws ClassCastException
     * 		if the class of the specified element
     * 		prevents it from being added to this queue
     * @throws NullPointerException
     * 		if the specified element is null and
     * 		this queue does not permit null elements
     * @throws IllegalArgumentException
     * 		if some property of this element
     * 		prevents it from being added to this queue
     */
    @Override
    public synchronized boolean offer(final E o) {
        return add(o);
    }

    /**
     * Retrieves and removes the head of this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    @Override
    public synchronized E poll() {
        if (size == 0) {
            return null;
        }

        return remove();
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
    public synchronized E element() {
        if (size == 0) {
            throw new NoSuchElementException("tried to get the head of an empty FCQueue");
        }

        return head.element;
    }

    /**
     * Retrieves, but does not remove, the head of this queue,
     * or returns {@code null} if this queue is empty.
     *
     * @return the head of this queue, or {@code null} if this queue is empty
     */
    @Override
    public synchronized E peek() {
        if (size == 0) {
            return null;
        }

        return head.element;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following implement FastCopyable
    //////////////////////////////////////////////////////////////////////////////////////////////////

    /** {@inheritDoc} */
    @Override
    public synchronized FCQueue<E> copy() {
        if (isImmutable()) {
            throw new IllegalStateException("Tried to make a copy of an immutable FCQueue");
        }

        final FCQueue<E> queue = new FCQueue<>(this);

        // there can be only one mutable per queue group. If the copy is, then this isn't.
        setImmutable(true);

        return queue;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following implement AbstractMerkleNode
    //////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    protected synchronized void destroyNode() {
        setImmutable(true);
        head = tail = null;
        size = 0;
        hash = NULL_HASH;
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
    public synchronized int size() {
        return size;
    }

    /**
     * Returns {@code true} if this collection contains no elements.
     *
     * @return {@code true} if this collection contains no elements
     */
    @Override
    public synchronized boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns {@code true} if this collection contains the specified element.
     * More formally, returns {@code true} if and only if this collection
     * contains at least one element {@code e} such that
     * {@code Objects.equals(o, e)}.
     *
     * @param o
     * 		element whose presence in this collection is to be tested
     * @return {@code true} if this collection contains the specified
     * 		element
     * @throws ClassCastException
     * 		if the type of the specified element
     * 		is incompatible with this collection
     * 		(<a href="{@docRoot}/java/util/Collection.html#optional-restrictions">optional</a>)
     * @throws NullPointerException
     * 		if the specified element is null and this
     * 		collection does not permit null elements
     * 		(<a href="{@docRoot}/java/util/Collection.html#optional-restrictions">optional</a>)
     */
    @Override
    public synchronized boolean contains(final Object o) {
        for (final E e : this) {
            if (Objects.equals(o, e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns an iterator over the elements in this queue, in insertion order (head first, tail last).
     *
     * @return an {@code Iterator} over the elements in this collection
     */
    @Override
    public synchronized Iterator<E> iterator() {
        return new Iterator<>() {
            Node<E> cur = head;
            final Node<E> end = tail;

            @Override
            public boolean hasNext() {
                return cur != end;
            }

            @Override
            public E next() {
                if (cur == null || cur == end) {
                    throw new NoSuchElementException();
                }

                final E result = cur.element;
                cur = cur.next;
                return result;
            }
        };
    }

    /**
     * Returns an iterator over the elements in this queue in reverse insertion order (tail first, head last).
     *
     * @return an {@code Iterator} over the elements in this collection in reverse order
     */
    public Iterator<E> reverseIterator() {
        final ArrayList<E> list = new ArrayList<>(this);
        Collections.reverse(list);
        return list.iterator();
    }

    /**
     * Returns an array containing all elements in this collection.
     * If this collection makes any guarantees as to what order its elements
     * are returned by its iterator, this method must return the elements in
     * the same order. The returned array's {@linkplain Class#getComponentType
     * runtime component type} is {@code Object}.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this collection.  (In other words, this method must
     * allocate a new array even if this collection is backed by an array).
     * The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array, whose {@linkplain Class#getComponentType runtime component
     * 		type} is {@code Object}, containing all elements in this collection
     */
    @Override
    public synchronized Object[] toArray() {
        final Object[] result = new Object[size()];
        int i = 0;

        for (final E e : this) {
            result[i++] = e;
        }

        return result;
    }

    /**
     * Returns an array containing all of the elements in this collection;
     * the runtime type of the returned array is that of the specified array.
     * If the collection fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this collection.
     *
     * <p>If this collection fits in the specified array with room to spare
     * (i.e., the array has more elements than this collection), the element
     * in the array immediately following the end of the collection is set to
     * {@code null}.  (This is useful in determining the length of this
     * collection <i>only</i> if the caller knows that this collection does
     * not contain any {@code null} elements.)
     *
     * <p>If this collection makes any guarantees as to what order its elements
     * are returned by its iterator, this method must return the elements in
     * the same order.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a collection known to contain only strings.
     * The following code can be used to dump the collection into a newly
     * allocated array of {@code String}:
     *
     * <pre>
     *     String[] y = x.toArray(new String[0]);</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a
     * 		the array into which the elements of this collection are to be
     * 		stored, if it is big enough; otherwise, a new array of the same
     * 		runtime type is allocated for this purpose.
     * @return an array containing all elements in this collection
     * @throws ArrayStoreException
     * 		if the runtime type of any element in this
     * 		collection is not assignable to the {@linkplain Class#getComponentType
     * 		runtime component type} of the specified array
     * @throws NullPointerException
     * 		if the specified array is null
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T[] toArray(T[] a) {
        final int currentSize = size();
        int i = 0;

        if (a.length < currentSize) {
            // If array is too small, allocate the new one with the same component type
            a = (T[]) Array.newInstance(a.getClass().getComponentType(), currentSize);
        } else if (a.length > currentSize) {
            // If array is too large, set the first unassigned element to null
            a[currentSize] = null;
        } else {
            // do nothing
        }

        for (final E e : this) {
            // No need for checked cast - ArrayStoreException will be thrown
            // if types are incompatible, just as required
            a[i++] = (T) e;
        }

        return a;
    }

    /**
     * This operation is not supported, and will throw an exception. The FCQueue is fast to copy because it ensures that
     * no element will ever be removed from the middle of the queue.
     *
     * @throws UnsupportedOperationException
     * 		always thrown because the {@code remove} operation
     * 		is not supported by this collection
     */
    @Override
    public boolean remove(final Object o) {
        throw new UnsupportedOperationException("FCQueue allows removal only from the head, not arbitrary removals");
    }

    /**
     * Returns {@code true} if this collection contains all elements
     * in the specified collection.
     *
     * @param c
     * 		collection to be checked for containment in this collection
     * @return {@code true} if this collection contains all elements
     * 		in the specified collection
     * @throws ClassCastException
     * 		if the types of one or more elements
     * 		in the specified collection are incompatible with this
     * 		collection
     * 		(<a href="{@docRoot}/java/util/Collection.html#optional-restrictions">optional</a>)
     * @throws NullPointerException
     * 		if the specified collection contains one
     * 		or more null elements and this collection does not permit null
     * 		elements
     * 		(<a href="{@docRoot}/java/util/Collection.html#optional-restrictions">optional</a>),
     * 		or if the specified collection is null.
     * @see #contains(Object)
     */
    @Override
    public synchronized boolean containsAll(final Collection<?> c) {
        // This could be made faster by sorting both lists (if c is larger than log of size()).
        // But we'll do brute force for now (which is better for small c).
        for (final Object e : c) {
            if (!contains(e)) {
                return false;
            }
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean addAll(final Collection<? extends E> c) {
        for (final E e : c) {
            add(e);
        }

        return false;
    }

    /**
     * This operation is not supported, and will throw an exception. The FCQueue is fast to copy because it ensures that
     * no element will ever be removed from the middle of the queue.
     *
     * @param c
     * 		collection containing elements to be removed from this collection
     * @throws UnsupportedOperationException
     * 		always thrown because the {@code removeAll} operation
     * 		is not supported by this collection
     */
    @Override
    public boolean removeAll(final Collection<?> c) {
        throw new UnsupportedOperationException("FCQueue can only remove from the head");
    }

    /**
     * This operation is not supported, and will throw an exception. The FCQueue is fast to copy because it ensures that
     * no element will ever be removed from the middle of the queue.
     *
     * @param c
     * 		collection containing elements to be retained in this collection
     * @throws UnsupportedOperationException
     * 		always thrown because the {@code retainAll} operation
     * 		is not supported by this collection
     */
    @Override
    public boolean retainAll(final Collection<?> c) {
        throw new UnsupportedOperationException("FCQueue can only remove from the head");
    }

    /**
     * Removes all elements from this queue.
     * The queue will be empty and the hash reset to the null value after this method returns.
     * This does not delete the FCQueue object. It just empties the queue.
     */
    @Override
    public synchronized void clear() {
        throwIfImmutable();

        head = tail;
        size = 0;
        hash = null;
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////
    // the following FastCopyable methods have default implementations, but are overridden here
    //////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final FCQueue<?> fcQueue = (FCQueue<?>) o;

        return size == fcQueue.size && Objects.equals(getHash(), fcQueue.getHash());
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(size);
        result = 31 * result + Objects.hashCode(getHash());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void serialize(final SerializableDataOutputStream out) throws IOException {
        detach();
        out.writeSerializableIterableWithSize(iterator(), size(), true, false);
    }

    /**
     * Create a shallow detached copy to avoid holding references to the entire queue should the original
     * copy remain reserved for a long time (e.g., during reconnect).
     * Detaching a mutable copy does not look like a good idea and there should be no use of that in production.
     */
    private void detach() {
        if (head == null || tail == null || isMutable()) {
            return;
        }
        // Ensure the copy is hashed before detaching
        getHash();

        Node<E> src = head;
        Node<E> dst = new Node<>();
        head = dst;
        while (src != tail) {
            dst.element = src.element;
            dst.runningHash = src.runningHash;
            dst.next = new Node<>();
            src = src.next;
            dst = dst.next;
        }
        dst.runningHash = src.runningHash;
        tail = dst;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        in.readSerializableIterableWithSize(MAX_ELEMENTS, this::add);
    }

    /**
     * Find the hash of a FastCopyable object.
     *
     * @param element
     * 		an element contained by this collection that is being added, deleted, or replaced
     * @return the 48-byte hash of the element (zero byte array if element is null)
     */
    byte[] getHash(final E element) {
        // Handle cases where list methods return null if the list is empty
        if (element == null) {
            return NULL_HASH_BYTES;
        }
        // return a hash of a hash, in order to make state proofs smaller in the future
        CRYPTOGRAPHY.digestSync(element);
        return CRYPTOGRAPHY.digestBytesSync(element.getHash());
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return ClassVersion.REMOVED_HASH;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.MIGRATE_TO_SERIALIZABLE;
    }
}
