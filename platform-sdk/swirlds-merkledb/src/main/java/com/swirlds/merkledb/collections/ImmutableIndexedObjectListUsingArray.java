// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * An implementation of ImmutableIndexedObjectList that stores its objects in an on-heap array. The
 * position of each object in the array is its self-reported index minus a fixed offset.
 *
 * This means lookups by self-reported index are extremely fast, but the array may waste some
 * space if self-reported indexes are not sequential.
 *
 * @param <T> the type of the IndexedObject in the list
 */
public class ImmutableIndexedObjectListUsingArray<T extends IndexedObject> implements ImmutableIndexedObjectList<T> {
    /**
     * Offset to subtract from self-reported index to get an object's position in the data array (if
     * present).
     */
    private final int firstIndexOffset;

    /**
     * Used to create arrays of T.
     */
    private final Function<Integer, T[]> arrayProvider;

    /**
     * The array of data objects, with each object positioned at its self-reported index minus the
     * firstIndexOffset.
     */
    private final T[] dataArray;

    /** Size of this list. Equal to the number of non-null elements in the data array. */
    private final int size;

    /** Creates a new ImmutableIndexedObjectList from an existing list of objects. */
    public ImmutableIndexedObjectListUsingArray(
            @NonNull final Function<Integer, T[]> arrProvider, @NonNull final List<T> objects) {
        Objects.requireNonNull(arrProvider);
        Objects.requireNonNull(objects);

        this.arrayProvider = arrProvider;

        final List<T> nonNullObjects = new ArrayList<>();
        for (final T object : objects) {
            if (object != null) {
                nonNullObjects.add(object);
            }
        }

        size = nonNullObjects.size();

        if (size == 0) {
            firstIndexOffset = 0;
            dataArray = null;
        } else {
            // Ensure data is sorted by self-reported index
            nonNullObjects.sort(Comparator.comparingInt(IndexedObject::getIndex));
            // Determine the first and last indexes
            final T firstObject = nonNullObjects.get(0);
            firstIndexOffset = firstObject.getIndex();
            final int lastIndex = nonNullObjects.get(nonNullObjects.size() - 1).getIndex();
            final int range = lastIndex - firstIndexOffset + 1;

            // Create a sufficiently large data array and place the nonNullObjects in the correct
            // positions
            dataArray = arrayProvider.apply(range);
            for (final T object : nonNullObjects) {
                dataArray[object.getIndex() - firstIndexOffset] = object;
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public ImmutableIndexedObjectListUsingArray<T> withAddedObject(final T newObject) {
        // Ignore null objects
        if (newObject == null) {
            return this;
        }

        // Create a temp array list with just non-null objects that belong to the new list
        final List<T> newDataArray = new ArrayList<>(Arrays.asList(dataArray));
        newDataArray.removeIf(next -> next == null || next.getIndex() == newObject.getIndex());
        newDataArray.add(newObject);

        return new ImmutableIndexedObjectListUsingArray<>(arrayProvider, newDataArray);
    }

    /** {@inheritDoc} */
    @Override
    public ImmutableIndexedObjectListUsingArray<T> withDeletedObjects(@NonNull final Collection<T> objectsToDelete) {
        // Share an immutable empty list
        if (objectsToDelete.isEmpty() || isEmpty()) {
            return this;
        }

        // Create a temp array list with just non-null objects that belong to the new list
        final List<T> newDataArray = new ArrayList<>();
        for (final T datum : dataArray) {
            if (datum == null) {
                continue;
            }

            if (objectsToDelete.contains(datum)) {
                continue;
            }

            newDataArray.add(datum);
        }

        return new ImmutableIndexedObjectListUsingArray<>(arrayProvider, newDataArray);
    }

    /** {@inheritDoc} */
    @Override
    public T getLast() {
        return isEmpty() ? null : dataArray[dataArray.length - 1];
    }

    /** {@inheritDoc} */
    @Override
    public T get(final int objectIndex) {
        if (objectIndex < 0) {
            throw new IndexOutOfBoundsException("Cannot use negative index " + objectIndex);
        }
        if (isEmpty()) {
            return null;
        }
        final int offsetIndex = objectIndex - firstIndexOffset;
        return (offsetIndex < 0 || offsetIndex >= dataArray.length) ? null : dataArray[offsetIndex];
    }

    /** {@inheritDoc} */
    @Override
    public Stream<T> stream() {
        return Arrays.stream(dataArray).filter(Objects::nonNull);
    }

    @Override
    public String toString() {
        return prettyPrintedIndices();
    }

    private boolean isEmpty() {
        /* We null out the data array when constructing from an empty list */
        return dataArray == null;
    }

    /** Get the number of items in this list */
    @Override
    public int size() {
        return size;
    }
}
