// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence.set;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.ToLongFunction;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * A lock free {@link SequenceSet}.
 *
 * @param <T> the type of the element contained within this set
 */
public class StandardSequenceSet<T> extends AbstractSequenceSet<T> {

    /**
     * Create a new lock free {@link SequenceSet} that does not permit expansion.
     *
     * @param lowestAllowedSequenceNumber the initial lowest permitted sequence in the set
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param getSequenceNumberFromEntry  given an entry, extract the sequence number
     */
    public StandardSequenceSet(
            final long lowestAllowedSequenceNumber,
            final int sequenceNumberCapacity,
            @NonNull final ToLongFunction<T> getSequenceNumberFromEntry) {

        super(lowestAllowedSequenceNumber, sequenceNumberCapacity, false, getSequenceNumberFromEntry);
    }

    /**
     * Create a new lock free {@link SequenceSet}.
     *
     * @param lowestAllowedSequenceNumber the initial lowest permitted sequence in the set
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param allowExpansion              if true, then instead of rejecting elements with a sequence number higher than
     *                                    the allowed by the current capacity, increase capacity and then insert the
     *                                    element. Does not expand if the sequence number is too low to fit in the
     *                                    current capacity.
     * @param getSequenceNumberFromEntry  given an entry, extract the sequence number
     */
    public StandardSequenceSet(
            final long lowestAllowedSequenceNumber,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            @NonNull final ToLongFunction<T> getSequenceNumberFromEntry) {

        super(lowestAllowedSequenceNumber, sequenceNumberCapacity, allowExpansion, getSequenceNumberFromEntry);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    protected SequenceMap<T, Boolean> buildMap(
            final long lowestAllowedSequenceNumber,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            @NonNull final ToLongFunction<T> getSequenceNumberFromEntry) {

        Objects.requireNonNull(getSequenceNumberFromEntry);

        return new StandardSequenceMap<>(
                lowestAllowedSequenceNumber, sequenceNumberCapacity, allowExpansion, getSequenceNumberFromEntry);
    }
}
