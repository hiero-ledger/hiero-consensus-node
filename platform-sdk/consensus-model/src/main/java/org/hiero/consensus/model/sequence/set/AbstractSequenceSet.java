// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence.set;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import org.hiero.consensus.model.sequence.map.SequenceMap;

/**
 * Boilerplate implementation for {@link SequenceSet}.
 *
 * @param <T> the type of the element contained within this set
 */
public abstract class AbstractSequenceSet<T> implements SequenceSet<T> {

    /**
     * Used to implement the set.
     */
    private final SequenceMap<T, Boolean> map;

    /**
     * Create a new abstract sequence set.
     *
     * @param lowestAllowedSequenceNumber the initial lowest permitted sequence in the set
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param allowExpansion              if true, then instead of rejecting elements with a sequence number higher than
     *                                    the allowed by the current capacity, increase capacity and then insert the
     *                                    element.
     * @param getSequenceNumberFromEntry  given an entry, extract the sequence number
     */
    protected AbstractSequenceSet(
            final long lowestAllowedSequenceNumber,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            @NonNull final ToLongFunction<T> getSequenceNumberFromEntry) {

        Objects.requireNonNull(getSequenceNumberFromEntry);

        map = buildMap(lowestAllowedSequenceNumber, sequenceNumberCapacity, allowExpansion, getSequenceNumberFromEntry);
    }

    /**
     * Build a map that is used to implement the set.
     *
     * @param lowestAllowedSequenceNumber the initial lowest permitted sequence in the set
     * @param sequenceNumberCapacity      the number of sequence numbers permitted to exist in this data structure. E.g.
     *                                    if the lowest allowed sequence number is 100 and the capacity is 10, then
     *                                    values with a sequence number between 100 and 109 (inclusive) will be allowed,
     *                                    and any value with a sequence number outside that range will be rejected.
     * @param allowExpansion              if true, then instead of rejecting elements with a sequence number higher than
     *                                    the allowed by the current capacity, increase capacity and then insert the
     *                                    element.
     * @param getSequenceNumberFromEntry  given an entry, extract the sequence number
     * @return a sequence map
     */
    @NonNull
    protected abstract SequenceMap<T, Boolean> buildMap(
            final long lowestAllowedSequenceNumber,
            final int sequenceNumberCapacity,
            final boolean allowExpansion,
            @NonNull final ToLongFunction<T> getSequenceNumberFromEntry);

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(@NonNull final T element) {
        Objects.requireNonNull(element, "element must not be null");
        return map.putIfAbsent(element, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(@NonNull final T element) {
        Objects.requireNonNull(element, "element must not be null");
        return map.remove(element) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(final T element) {
        return map.containsKey(element);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeSequenceNumber(final long sequenceNumber, final Consumer<T> removedElementHandler) {
        map.removeValuesWithSequenceNumber(
                sequenceNumber, removedElementHandler == null ? null : ((k, v) -> removedElementHandler.accept(k)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<T> getEntriesWithSequenceNumber(final long sequenceNumber) {
        return map.getKeysWithSequenceNumber(sequenceNumber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shiftWindow(final long lowestAllowedSequenceNumber, final Consumer<T> removedElementHandler) {
        map.shiftWindow(
                lowestAllowedSequenceNumber,
                removedElementHandler == null ? null : ((k, v) -> removedElementHandler.accept(k)));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSequenceNumberCapacity() {
        return map.getSequenceNumberCapacity();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSize() {
        return map.getSize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getFirstSequenceNumberInWindow() {
        return map.getFirstSequenceNumberInWindow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLastSequenceNumberInWindow() {
        return map.getLastSequenceNumberInWindow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        map.clear();
    }
}
