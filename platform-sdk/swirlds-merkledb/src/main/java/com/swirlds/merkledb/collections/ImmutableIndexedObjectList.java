// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

/**
 * An immutable list of indexed objects, containing at most one object at any given index.
 *
 * The {@link ImmutableIndexedObjectList#withAddedObject(IndexedObject)} and
 * {@link ImmutableIndexedObjectList#withDeletedObjects(Collection)}} methods return shallow copies
 * of the list that result from applying the requested addition or deletion(s), leaving the
 * receiving list unchanged.
 *
 * @param <T>
 * 		the type of IndexedObject in the list
 */
@SuppressWarnings("unused")
public interface ImmutableIndexedObjectList<T extends IndexedObject> {
    /**
     * Creates a new ImmutableIndexedObjectList with the union of the existing objects
     * and the given new object, minus any existing object at the new object's index.
     *
     * Returns this list if given a null object.
     *
     * @param newT
     * 		a new indexed object
     * @return an immutable copy of this list plus newT, minus any existing element at newT's index
     */
    ImmutableIndexedObjectList<T> withAddedObject(T newT);

    /**
     * Creates a new ImmutableIndexedObjectList with the existing objects, minus any
     * at indexes in a set of objects (indexes) to delete.
     *
     * @param objectsToDelete a non-null set of objects to delete
     * @return an immutable copy of this list minus any existing elements at indices from the deletion set
     */
    ImmutableIndexedObjectList<T> withDeletedObjects(@NonNull Collection<T> objectsToDelete);

    /**
     * Gets the last object in this list.
     *
     * @return the object in this list with the greatest index, or null if the list is empty
     */
    T getLast();

    /**
     * Gets the object at given index.
     *
     * @return the unique object at the given index, null if this list has no such object
     * @throws IndexOutOfBoundsException
     * 		if the index is negative
     */
    T get(final int objectIndex);

    /**
     * Gets a stream containing all non-null objects in this list, sorted in ascending order
     * of their self-reported index. (Note that an object's index need not be its position in
     * the returned stream; for example, the index of the first object in the stream
     * could be 7 and not 0.)
     *
     * @return a stream of this list's objects, sorted by self-reported index
     */
    Stream<T> stream();

    /**
     * Useful debugging helper that prints just the indices of the objects in this list.
     *
     * @return a pretty-printed list of the self-reported indices in the list
     */
    default String prettyPrintedIndices() {
        return Arrays.toString(stream().mapToInt(IndexedObject::getIndex).toArray());
    }

    /**
     * Get the number of items in this list
     */
    int size();
}
