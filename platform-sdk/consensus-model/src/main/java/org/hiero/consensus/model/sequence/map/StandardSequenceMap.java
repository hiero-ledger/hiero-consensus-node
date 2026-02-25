// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence.map;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * A lock free implementation of {@link SequenceMap}.
 *
 * @param <K> the type of the key
 * @param <V> the type of the value
 */
public class StandardSequenceMap<K, V> extends AbstractSequenceMap<K, V> {

    private long firstSequenceNumberInWindow;

    /**
     * Construct a {@link SequenceMap} that does not permit expansion.
     *
     * @param firstSequenceNumberInWindow the lowest allowed sequence number
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param getSequenceNumberFromKey    a method that extracts the sequence number from a key
     */
    public StandardSequenceMap(
            final long firstSequenceNumberInWindow,
            final int sequenceNumberCapacity,
            final ToLongFunction<K> getSequenceNumberFromKey) {

        this(firstSequenceNumberInWindow, sequenceNumberCapacity, false, getSequenceNumberFromKey);
    }

    /**
     * Construct a {@link SequenceMap}.
     *
     * @param firstSequenceNumberInWindow the lowest allowed sequence number
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param allowExpansion              if true, then instead of rejecting elements with a sequence number higher than
     *                                    the allowed by the current capacity, increase capacity and then insert the
     *                                    element. Does not expand if the sequence number is too low to fit in the
     *                                    current capacity.
     * @param getSequenceNumberFromKey    a method that extracts the sequence number from a key
     */
    public StandardSequenceMap(
            final long firstSequenceNumberInWindow,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            final ToLongFunction<K> getSequenceNumberFromKey) {

        super(firstSequenceNumberInWindow, sequenceNumberCapacity, allowExpansion, getSequenceNumberFromKey);
        this.firstSequenceNumberInWindow = firstSequenceNumberInWindow;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getFirstSequenceNumberInWindow() {
        return firstSequenceNumberInWindow;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setFirstSequenceNumberInWindow(final long firstSequenceNumberInWindow) {
        this.firstSequenceNumberInWindow = firstSequenceNumberInWindow;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<K, V> buildDataMap() {
        return new HashMap<>();
    }
}
