// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 * During reconnect, we have a thread that is sending nodes, and another thread
 * receiving information about the nodes the learner has. We need to keep track
 * of the nodes the learner has notified it knows about, so the sender thread
 * can skip those nodes. Notice that when one node is known by the learner, their
 * descendants are also known. At the moment the teacher receives the notification
 * that the learner has a node, the teacher might have already sent that node,
 * but still any descendant of that node is already known by the learner, and we
 * shouldn't send it or try to send the least possible amount of known nodes.
 * </p>
 * This class keeps track of the status of each node the learner has notified us
 * about. The status is classified as:
 * <ul>
 *     <li><strong>UNKNOWN</strong>: We don't know if the learner knows or doesn't
 *     know about about a node
 *     </li>
 *     <li><strong>KNOWN</strong>: The learner has notified us that it knows
 *     about the node, as well as all of its descendants
 *     </li>
 *     <li><strong>NOT_KNOWN</strong>: The learner has notified us that it
 *     doesn't know about the node, but we can't make any assumption about
 *     the descendants
 *     </li>
 * </ul>
 *
 * This class is thread safe supporting multiple concurrent readers but only
 * one concurrent writer.
 */
public final class ConcurrentNodeStatusTracker {

    /**
     * Node status
     */
    public enum Status {
        UNKNOWN,
        KNOWN,
        NOT_KNOWN
    }

    /**
     * Maximum number of elements per BitSet.
     * Currently, at 2^30 to improve performance on divisions
     */
    private static final int LIMIT = 0x40000000;

    /**
     * To speed up division, instead of doing {@code n / LIMIT},
     * we can compute it as {@code n >> lg(LIMIT)}, i.e., as
     * {@code n >> RIGHT_SHIFTS_FOR_LIMIT_AS_DIVISOR}.
     */
    private static final int RIGHT_SHIFTS_FOR_LIMIT_AS_DIVISOR = 30;

    /**
     * Value to compute {@code n % LIMIT} with the &amp; operator
     * to improve performance. Instead of {@code n % LIMIT},
     * we should use {@code n & DIVISOR}.
     */
    private static final int DIVISOR = LIMIT - 1;

    /**
     * Map to keep track of the status of the bits for which we know their status.
     * Given that each {@link BitSetGroup} supports up to {@value LIMIT} elements,
     * for each value, we compute the quotient {@code value / }{@value LIMIT}, which
     * provides the {@code bitsetIndex}, which is used as a key for this map, and
     * then the remainder {@code value % }{@value LIMIT} gives us the index at each
     * {@link BitSetGroup}.
     */
    private final ConcurrentHashMap<Long, BitSetGroup> statusBitSets;

    /**
     * Upper bound of the values to track
     */
    private final long capacity;

    /**
     * Creates a new {@link ConcurrentNodeStatusTracker} with a specified capacity.
     * The capacity is important because when a node is set as <strong>KNOWN</strong>
     * we iterate through all of its descendants, and we need to know when to stop.
     * The capacity specifies the maximum number of nodes in the tree.
     *
     * @param capacity
     * 		maximum number of nodes to track
     */
    public ConcurrentNodeStatusTracker(final long capacity) {
        this.capacity = capacity;
        this.statusBitSets = new ConcurrentHashMap<>();
        // we set the root as NOT_KNOW, otherwise there wouldn't be a
        // need to reconnect a VirtualMap
        this.setNodeStatus(0, 0, Status.NOT_KNOWN);
    }

    /**
     * For a node (specified as a long), we specified its status based
     * on the information provided by the learner. By default, the status
     * of each known is <strong>UNKNOWN</strong>, and setting a value with
     * a status to <strong>UNKNOWN</strong> wil throw an {@link IllegalArgumentException}
     * because it could be due to a bug that might try to set the status
     * of a node that already has a status set. Even though the current
     * status might be <strong>UNKNOWN</strong> setting it again to be
     * <strong>UNKNOWN</strong> would just unnecessary lock on the internal
     * {@link BitSet} used to track the values, causing an increase of
     * unnecessary contention.
     *
     * @param value
     * 		path of the node
     * @param status
     * 		the status the learner has reported
     * @throws IllegalArgumentException
     * 		if value is zero or less, or equal or greater than the capacity,
     * 		or if the status is <strong>UNKNOWN</strong>
     */
    public void set(final long value, final Status status) {
        if (value <= 0 || value >= capacity) {
            throw new IllegalArgumentException(
                    String.format("Value can only be between [0, %d), %d is illegal", capacity, value));
        }

        if (status == Status.UNKNOWN) {
            throw new IllegalArgumentException("Status can only be set to KNOWN or NOT_KNOWN");
        }

        final int index = getIndexInBitSetFor(value);
        final long bitSetIndex = getBitSetIndexFor(value);
        setNodeStatus(bitSetIndex, index, status);
    }

    /**
     * <p>
     * Gets the status of a node. If the current status of a node
     * is <strong>UNKNOWN</strong>, then we check the status of
     * its ascendants, until we find <strong>KNOWN</strong> or
     * <strong>UNKNOWN</strong>.
     * </p>
     * <p>
     * Notice that one ascendant could have a status of
     * <strong>NOT_KNOWN</strong>, and we would send
     * that node to the learner. Later on, we receive the notification
     * that the learner knows the node, so we sent that node
     * unnecessarily, but at the point of making the decision of
     * sending the node, <strong>UNKNOWN</strong> or <strong>NOT_KNOWN</strong>
     * yield the same result, i.e., we send the node anyway.
     * </p>
     * <p>
     * If one ascendant is <strong>KNOWN</strong>, then we return
     * <strong>KNOWN</strong>
     * </p>
     * <p>
     * In an <strong>UNKNOWN</strong> scenario the algorithmic complexity of
     * this method is {@code O(lg n)} where {@code n} is the {@code value}
     * provided. Otherwise, the method is in {@code O(1)}.
     * </p>
     * <p>
     * Currently, for each check of the status of a node, either direct
     * or its descendants, executes an atomic operation that blocks the
     * thread setting status.
     * </p>
     *
     * @param value
     * 		path of node to check
     * @return status of a node
     */
    public Status getStatus(long value) {
        if (value < 0 || value >= capacity) {
            throw new IllegalArgumentException(
                    String.format("Value can only be between [0, %d), %d is illegal", capacity, value));
        }

        Status status;
        do {
            final int index = getIndexInBitSetFor(value);
            final long bitSetIndex = getBitSetIndexFor(value);
            status = statusBitSets
                    .computeIfAbsent(bitSetIndex, k -> new BitSetGroup())
                    .getStatus(index);
            value = Path.getParentPath(value);
        } while (status == Status.UNKNOWN);

        return status;
    }

    /**
     * Get the status of a node as reported by the learner, or return UNKNOWN.
     * <p>
     * Unlike the getStatus(long value) method above, this method returns the actual
     * status of the requested node without traversing the tree to its parents.
     * If the learner hasn't reported a status for this particular node, this method
     * returns UNKNOWN.
     *
     * @param value path of node to check
     * @return status of the node, or UNKNOWN if its status has never been reported yet
     */
    public Status getReportedStatus(long value) {
        if (value < 0 || value >= capacity) {
            throw new IllegalArgumentException(
                    String.format("Value can only be between [0, %d), %d is illegal", capacity, value));
        }

        final int index = getIndexInBitSetFor(value);
        final long bitSetIndex = getBitSetIndexFor(value);
        return statusBitSets
                .computeIfAbsent(bitSetIndex, k -> new BitSetGroup())
                .getStatus(index);
    }

    /**
     * Atomically sets the status of a node represented by its value (path).
     * Currently, for the immediate use case, we are blocking for each
     * read and write, given that at much, we are using 2*{@value Integer#MAX_VALUE},
     * we only need 3 {@link BitSetGroup}s, and for much of the updates we
     * are going to be blocking.
     *
     * @param bitsetIndex
     * 		index of the {@link BitSetGroup}
     * @param valueIndex
     * 		index of the value inside the {@link BitSetGroup}
     * @param status
     * 		status to set
     */
    private void setNodeStatus(final long bitsetIndex, final int valueIndex, final Status status) {
        statusBitSets.compute(bitsetIndex, (k, bitsetGroup) -> {
            if (bitsetGroup == null) {
                bitsetGroup = new BitSetGroup();
            }

            bitsetGroup.setStatus(valueIndex, status);
            return bitsetGroup;
        });
    }

    /**
     * Computes the index of the BitSet this value
     * should be set in
     *
     * @param value
     * 		Value to set/retrieve
     * @return index of the BitSet this value should be set in
     */
    private static long getBitSetIndexFor(final long value) {
        return value >> RIGHT_SHIFTS_FOR_LIMIT_AS_DIVISOR;
    }

    /**
     * Computes the index in the BitSet this value
     * should be set in
     *
     * @param value
     * 		Value to set/retrieve
     * @return index in the BitSet this value should be set in
     */
    private static int getIndexInBitSetFor(final long value) {
        return (int) (value & DIVISOR);
    }

    /**
     * We use two {@link BitSet} to handle the 3 possible
     * states of a value:
     * <ul>
     * <li><strong>UNKNOWN</strong>: if {@code knowns} is set to false.
     * </li>
     * <li><strong>KNOWN</strong>: if {@code knowns} is set to true and
     * {@code status} is also set to true.
     * </li>
     * <li><strong>NOT_KNOWN</strong>: if {@code knowns} is set to true
     * and {@code status} is not set.
     * </li>
     * </ul>
     */
    private static final class BitSetGroup {
        private final BitSet status;
        private final BitSet knowns;

        private BitSetGroup() {
            this.status = new BitSet(LIMIT);
            this.knowns = new BitSet(LIMIT);
        }

        private void setStatus(final int index, final Status status) {
            this.knowns.set(index);
            if (status == Status.KNOWN) {
                this.status.set(index);
            }
        }

        private Status getStatus(final int index) {
            if (!this.knowns.get(index)) {
                return Status.UNKNOWN;
            }

            return status.get(index) ? Status.KNOWN : Status.NOT_KNOWN;
        }
    }
}
