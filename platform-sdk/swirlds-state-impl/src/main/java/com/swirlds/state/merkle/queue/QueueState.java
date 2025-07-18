// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.queue;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the state of a queue, tracking its head and tail indices.
 *
 * <p><b>Why is it needed?</b></p>
 * This class encapsulates the metadata required for managing a queue, such as its head and tail
 * indices. It provides utilities for serialization, size computation, and determining if the queue
 * is empty. By abstracting this information, the queue's state can be stored, retrieved, and updated
 * effectively, making it integral to on-disk queue implementations.
 *
 * <p><b>What does it do?</b></p>
 * This class:
 * <ul>
 *     <li>Tracks the head (next index to retrieve) and tail (next index to add) of the queue.</li>
 *     <li>Determines queue size and whether it is empty.</li>
 *     <li>Handles serialization and deserialization of the queue state for persistence.</li>
 * </ul>
 *
 * <p><b>Where is it used?</b></p>
 * This class is used in queue implementations that rely on disk-backed storage, such as in
 * conjunction with the {@link com.swirlds.state.merkle.disk.OnDiskQueueHelper}, to persist the state
 * of the queue and manage its metadata.
 */
public class QueueState {

    /**
     * Queue head index. The index at which the next element is retrieved from the queue.
     * If {@code head == tail}, the queue is empty.
     */
    private long head = 1;

    /**
     * Queue tail index. The index at which the next element is added to the queue.
     * The size of the queue is calculated as {@code tail - head}.
     */
    private long tail = 1;

    /**
     * Creates a new queue state with default head and tail indices.
     */
    public QueueState() {}

    /**
     * Create a new {@link QueueState} from the given bytes.
     *
     * @param bytes The bytes to read. Cannot be null.
     */
    public QueueState(@NonNull final Bytes bytes) {
        this(bytes.toReadableSequentialData());
    }

    /**
     * Creates a queue state by reading its metadata from the given input stream.
     *
     * @param in the input from which the head and tail indices are read
     */
    public QueueState(final ReadableSequentialData in) {
        this.head = in.readLong();
        this.tail = in.readLong();
    }

    /**
     * Creates a queue state with the specified head and tail indices.
     *
     * @param head the initial head index
     * @param tail the initial tail index
     */
    public QueueState(long head, long tail) {
        this.head = head;
        this.tail = tail;
    }

    /**
     * Writes the state (head and tail indices) to the given output stream.
     *
     * @param out the output to which the state is written
     */
    public void writeTo(final WritableSequentialData out) {
        out.writeLong(head);
        out.writeLong(tail);
    }

    /**
     * Returns the size of the serialized state in bytes.
     *
     * @return the size in bytes required to serialize the queue state (16 bytes)
     */
    public int getSizeInBytes() {
        return 2 * Long.BYTES;
    }

    /**
     * Gets the head index.
     *
     * @return the current head index
     */
    public long getHead() {
        return head;
    }

    /**
     * Gets the head index and increments it by one. This operation is not thread-safe.
     *
     * @return the head index before incrementing
     */
    public long getHeadAndIncrement() {
        return head++;
    }

    /**
     * Gets the tail index.
     *
     * @return the current tail index
     */
    public long getTail() {
        return tail;
    }

    /**
     * Gets the tail index and increments it by one. This operation is not thread-safe.
     *
     * @return the tail index before incrementing
     */
    public long getTailAndIncrement() {
        return tail++;
    }

    /**
     * Determines if the queue represented by this state is empty.
     *
     * @return {@code true} if the queue is empty, otherwise {@code false}
     */
    public boolean isEmpty() {
        return head == tail;
    }

    @Override
    public String toString() {
        return "QueueState{" + "head=" + head + ", tail=" + tail + '}';
    }
}
