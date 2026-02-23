// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

/**
 * A base implementation of {@link WritableQueueState} that buffers all modifications and reads.
 * @param <E> The type of element in the queue.
 */
public abstract class WritableQueueStateBase<E> implements WritableQueueState<E> {

    /** Each element that has been read. At the moment these are not exposed, but could be. */
    private final List<E> readElements = new ArrayList<>();

    /** Each element that has been added to the queue, but not yet committed. */
    private final List<E> addedElements = new ArrayList<>();

    /** Listeners to be notified when the queue changes. */
    private final List<QueueChangeListener<E>> listeners = new ArrayList<>();

    /**
     * The current index into {@link #addedElements} that we have read from.
     *
     * <p>When a client reads from the queue (either with peek, poll, remove, or iterator) we will first read from
     * the backing data source, but after we have read everything from there, we also need to read from the
     * {@link #addedElements} list. This index keeps track of where we have read from it.
     */
    private int currentAddedElementIndex = 0;

    /** An iterator from the backing datasource for reading data */
    private Iterator<E> dsIterator = null;

    /** The cached most recent peeked element */
    private E peekedElement = null;

    /** State label used in logs, typically serviceName.stateKey */
    protected final String label;

    /** The state key. */
    protected final int stateId;

    /** Create a new instance */
    protected WritableQueueStateBase(final int stateId, final String label) {
        this.stateId = stateId;
        this.label = label;
    }

    /**
     * Gets whether this queue has been modified, either by reading elements off the queue, or by adding elements to
     * the queue.
     *
     * @return If the queue has been modified.
     */
    public boolean isModified() {
        return !readElements.isEmpty() || !addedElements.isEmpty();
    }

    /**
     * Register a listener to be notified of changes to the state on {@link #commit()}. We do not support unregistering
     * a listener, as the lifecycle of a {@link WritableQueueState} is scoped to the set of mutations made to a state in
     * a round; and there is no case where an application would only want to be notified of a subset of those changes.
     * @param listener the listener to register
     */
    public void registerListener(@NonNull final QueueChangeListener<E> listener) {
        requireNonNull(listener);
        listeners.add(listener);
    }

    /**
     * Flushes all changes into the underlying data store. This method should <strong>ONLY</strong>
     * be called by the code that created the {@link WritableQueueStateBase} instance or owns it. Don't
     * cast and commit unless you own the instance!
     */
    public final void commit() {
        // We only want to remove from the data source elements we actually read from there; not
        // any elements we read from the list of items added in this changeset; if we have peeked
        // a non-zero number of elements from the added list, then we need to subtract one if the
        // peeked element is not null, since it was only peeked and not read
        final var numReadFromAdded =
                currentAddedElementIndex > 0 ? currentAddedElementIndex - (peekedElement == null ? 0 : 1) : 0;
        for (int i = 0, n = readElements.size() - numReadFromAdded; i < n; i++) {
            removeFromDataSource();
            listeners.forEach(QueueChangeListener::queuePopChange);
        }

        // We only want to add to the data source elements that were added and NOT subsequently read
        for (int i = numReadFromAdded, n = addedElements.size(); i < n; i++) {
            final var addedElement = addedElements.get(i);
            addToDataSource(addedElement);
            listeners.forEach(l -> l.queuePushChange(addedElement));
        }
        reset();
    }

    /**
     * Clears the set of peeked values and added values.
     */
    public final void reset() {
        readElements.clear();
        addedElements.clear();
        peekedElement = null;
        dsIterator = null;
        currentAddedElementIndex = 0;
    }

    @Override
    public int getStateId() {
        return stateId;
    }

    @Nullable
    @Override
    // Suppressing the warning about the nested ternary operations
    @SuppressWarnings("java:S3358")
    public E peek() {
        if (peekedElement != null) return peekedElement;
        if (dsIterator == null) dsIterator = iterateOnDataSource();
        peekedElement = dsIterator.hasNext()
                ? dsIterator.next()
                : currentAddedElementIndex < addedElements.size()
                        ? addedElements.get(currentAddedElementIndex++)
                        : null;
        return peekedElement;
    }

    @Override
    public void add(@NonNull final E element) {
        addedElements.add(element);
    }

    @Nullable
    @Override
    public E removeIf(@NonNull final Predicate<E> predicate) {
        final var element = peek();

        if (element == null) {
            return null;
        }

        if (predicate.test(element)) {
            readElements.add(element);
            peekedElement = null;
            return element;
        }

        return null;
    }

    @NonNull
    @Override
    public Iterator<E> iterator() {
        final var iterator = iterateOnDataSource();
        final var addedElementsIterator = addedElements.iterator();
        final var numAddedElements = addedElements.size();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext() || addedElementsIterator.hasNext();
            }

            @Override
            public E next() {
                if (numAddedElements != addedElements.size()) {
                    throw new ConcurrentModificationException();
                }
                return iterator.hasNext() ? iterator.next() : addedElementsIterator.next();
            }
        };
    }

    /**
     * Adds the given element to the end of the data source.
     * @param element The element to add
     */
    protected abstract void addToDataSource(@NonNull final E element);

    /**
     * Removes the current item at the head of the data source queue.
     */
    protected abstract void removeFromDataSource();

    /**
     * Creates and returns an iterator over all data in the data source.
     * @return An iterator
     */
    @NonNull
    protected abstract Iterator<E> iterateOnDataSource();
}
