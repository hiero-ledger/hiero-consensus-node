// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap.internal;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.utility.UnmodifiableIterator;
import com.swirlds.fchashmap.FCHashMap;
import com.swirlds.fchashmap.ModifiableValue;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.hiero.base.ValueReference;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.Locked;

/**
 * A family of {@link FCHashMap}s. Each map in the family is a descendant-copy or an ancestor-copy of all other
 * maps in the family. The newest map in the family is always mutable, and any older maps in the family are always
 * immutable.
 *
 * @param <K>
 * 		the type of the keys in this family of maps
 * @param <V>
 * 		the type of the values in this family of maps
 */
public class FCHashMapFamily<K, V> {

    private static final float LOAD_FACTOR = 0.75F;
    private static final int CONCURRENCY_LEVEL = 1024;

    /**
     * Contains the data of all copies that have not been purged.
     */
    private final Map<K, Mutation<V>> data;

    /**
     * Tracks maps that need to be purged when they are deleted.
     */
    private final Map<Long, UnPurgedMap<K, V>> mapsNeedingPurging = new ConcurrentHashMap<>();

    /**
     * The youngest map in the family. Only this map is mutable. This forms one end of the
     * linked list that is made up of {@link UnPurgedMap} copies.
     */
    private UnPurgedMap<K, V> mutableMap;

    /**
     * The oldest map in the family that has not yet been deleted. This forms one end of the
     * linked list that is made up of {@link UnPurgedMap} copies.
     */
    private UnPurgedMap<K, V> oldestMap;

    /**
     * Prevents concurrent deletion of copies within the family.
     */
    private final AutoClosableLock deletionLock = Locks.createAutoLock();

    /**
     * Initiate a family of {@link FCHashMap}s.
     *
     * @param capacity
     * 		the initial capacity of the map
     */
    public FCHashMapFamily(final int capacity) {
        data = new ConcurrentHashMap<>(capacity, LOAD_FACTOR, CONCURRENCY_LEVEL);
        mutableMap = new UnPurgedMap<>(0L);
        oldestMap = mutableMap;
        mapsNeedingPurging.put(0L, mutableMap);
    }

    /**
     * Get an iterator that walks over the keys in this family of maps.
     */
    public Iterator<K> keyIterator() {
        return new UnmodifiableIterator<>(data.keySet().iterator());
    }

    /**
     * Get the underlying data.
     */
    public Map<K, Mutation<V>> getData() {
        return data;
    }

    /**
     * This must be called every time a new {@link FCHashMap} copy is created.
     *
     * @return the version of the new copy
     */
    public long copyMap() {

        if (mutableMap == null) {
            throw new IllegalStateException(
                    "The mutable copy of the map has been released, no further copies are permitted");
        }

        final long nextVersion = mutableMap.getVersion() + 1;

        final UnPurgedMap<K, V> newMap = new UnPurgedMap<>(nextVersion);
        mapsNeedingPurging.put(nextVersion, newMap);

        mutableMap.setNext(newMap);
        newMap.setPrevious(mutableMap);

        mutableMap = newMap;

        return nextVersion;
    }

    /**
     * Updates the latest value, either creating a new mutation or updating the most recent one.
     *
     * @param version
     * 		the current version of the mutable map
     * @param value
     * 		the value we would like to set, or null if we are signifying a deletion
     * @param originalValueReference
     * 		after this operation, this should reference the original value
     * @param mutationToPurge
     * 		after this operation, this should reference a mutation that will eventually need to be purged, or null if
     * 		there
     * 		is no mutation that will eventually need to be purged
     * @param <K>
     * 		the type of the key
     * @param <V>
     * 		the type of the value
     */
    private record MutateHandler<K, V>(
            long version,
            V value,
            ValueReference<V> originalValueReference,
            ValueReference<Mutation<V>> mutationToPurge)
            implements BiFunction<K, Mutation<V>, Mutation<V>> {

        /**
         * Perform a get-for-modify operation.
         *
         * @param key
         * 		the key we are performing an update on
         * @param mutationHead
         * 		the original head of the mutation list, is the latest mutation at the start of the operation
         * @return what should become the new head of the mutation list,
         * 		or null if the key should be removed from the map
         */
        @Override
        public Mutation<V> apply(final K key, final Mutation<V> mutationHead) {
            originalValueReference.setValue(mutationHead == null ? null : mutationHead.getValue());

            final Mutation<V> mutation;
            if (mutationHead != null && mutationHead.getVersion() == version) {
                // mutation for this version already exists
                mutation = mutationHead;
                mutation.setValue(value);
            } else {
                // mutation for this version does not yet exist
                mutation = new Mutation<>(version, value, mutationHead);

                if (mutationHead != null) {
                    // If mutationHead is not null, then this list now contains at least two entries. All lists
                    // with more than one entry will eventually require purging.
                    mutationToPurge.setValue(mutationHead);
                }
            }

            if (value == null && mutation.getPrevious() == null) {
                // If the only remaining mutation is a deletion then it is safe to remove the key from the map
                return null;
            }

            return mutation;
        }
    }

    /**
     * Update the value for a key at this version. Must only be called on mutable copies.
     *
     * @param key
     * 		the key associated that will hold the new value
     * @param value
     * 		the new value, or null if this operation signifies a deletion.
     * @param size
     * 		an atomic integer that tracks the size of the map
     * @return the original value, or null if originally deleted
     */
    public V mutate(final K key, final V value, final AtomicInteger size) {

        final long version = mutableMap.getVersion();

        final ValueReference<V> originalValueReference = new ValueReference<>();
        final ValueReference<Mutation<V>> mutationToPurge = new ValueReference<>(null);

        // update the value in the list of mutations
        data.compute(key, new MutateHandler<>(version, value, originalValueReference, mutationToPurge));

        // update size of the map
        final V originalValue = originalValueReference.getValue();
        if (originalValue == null && value != null) {
            size.getAndIncrement();
        } else if (originalValue != null && value == null) {
            size.getAndDecrement();
        }

        if (mutationToPurge.getValue() != null) {
            schedulePurging(key, mutationToPurge.getValue());
        }

        return originalValue;
    }

    /**
     * Look up the most recent mutation that does not exceed a map's version.
     *
     * @param version
     * 		the version of the map to look up the mutation for
     * @param key
     * 		look up the mutation for this key
     * @return The mutation that corresponds to the version. May be null if the key is not in the map at this version.
     */
    public Mutation<V> getMutation(final long version, final K key) {
        Mutation<V> mutation = data.get(key);

        // It is safe to traverse this list without thread synchronization. Any new mutations added
        // are guaranteed to be added at the head of the list, where we will not look. Any mutation
        // that is deleted from this list is guaranteed to be one we don't want to return, since a mutation
        // can only be deleted if no maps can reach it. It will be a race if we see the mutation or not,
        // but if we do see it we ignore it, so either branch of the race is indistinguishable. References
        // are atomic in java, so it is no danger to read the link references while they are changing in
        // this scenario. Deleted links maintain their links, so it is perfectly safe to traverse them.
        while (mutation != null && mutation.getVersion() > version) {
            mutation = mutation.getPrevious();
        }
        return mutation;
    }

    /**
     * Performs a get-for-modify operation on a linked list of mutations.
     *
     * @param version
     * 		the version of the mutable map
     * @param original
     * 		this object will be populated with the original value
     * @param <K>
     * 		the type of the key
     * @param <V>
     * 		the type of the value
     */
    private record GetForModifyHandler<K, V>(long version, ValueReference<V> original)
            implements BiFunction<K, Mutation<V>, Mutation<V>> {

        /**
         * Perform a get-for-modify operation.
         *
         * @param key
         * 		the key we are performing the get-for-modify on
         * @param mutationHead
         * 		the original head of the mutation list, is the latest mutation at the start of the operation
         * @return what should become the new head of the mutation list
         */
        @SuppressWarnings("unchecked")
        @Override
        public Mutation<V> apply(final K key, final Mutation<V> mutationHead) {
            if (mutationHead == null) {
                return null;
            }

            original.setValue(mutationHead.getValue());

            if (mutationHead.getVersion() == version || mutationHead.getValue() == null) {
                return mutationHead;
            }

            return new Mutation<>(version, ((FastCopyable) mutationHead.getValue()).copy(), mutationHead);
        }
    }

    /**
     * <p>
     * Get a value that is safe to directly modify. If value has been modified this round then return it.
     * If value was modified in a previous round, call {@link FastCopyable#copy()} on it, insert it into
     * the map, and return it. If the value is null, then return null.
     * </p>
     *
     * <p>
     * It is not necessary to manually re-insert the returned value back into the map.
     * </p>
     *
     * <p>
     * This method is only permitted to be used on maps that contain values that implement {@link FastCopyable}.
     * Using this method on maps that contain values that do not implement {@link FastCopyable} will
     * result in undefined behavior.
     * </p>
     *
     * @param key
     * 		the key
     * @return a {@link ModifiableValue} that contains a value is safe to directly modify, or null if the key
     * 		is not in the map
     */
    public ModifiableValue<V> getForModify(final K key) {

        final long version = mutableMap.getVersion();
        final ValueReference<V> original = new ValueReference<>();
        final Mutation<V> mutation = data.compute(key, new GetForModifyHandler<>(version, original));

        if (mutation == null || mutation.getValue() == null) {
            return null;
        }

        if (mutation.getValue() != original.getValue() && mutation.getPrevious() != null) {
            schedulePurging(key, mutation.getPrevious());
        }

        return new ModifiableValue<>(mutation.getValue(), original.getValue());
    }

    /**
     * Schedule future purging for a key.
     *
     * @param key
     * 		the key that needs purging
     * @param mutation
     * 		the mutation that needs to be purged
     */
    private void schedulePurging(final K key, final Mutation<V> mutation) {
        // We need to find a map that has not yet been deleted to take
        // responsibility for the purging for this key.

        UnPurgedMap<K, V> purgingMap = mutableMap.getPrevious();
        while (purgingMap != null) {
            if (purgingMap.schedulePurging(key, mutation)) {
                // We have found a map that is willing to purge for this key.
                break;
            }

            purgingMap = purgingMap.getPrevious();
        }

        if (purgingMap == null) {
            // There were no maps that were willing to purge for this key.
            mutableMap.schedulePurging(key, mutation);
        }
    }

    /**
     * For each version, deleted and undeleted, between the highest and lowest undeleted version (inclusive), find the
     * first undeleted greater or equal version.
     *
     * @return a map of version to first undeleted greater or equal version
     */
    private Map<Long, Long> buildNextUndeletedVersionMap() {
        final Map<Long, Long> nextUndeletedVersionMap = new HashMap<>();

        UnPurgedMap<K, V> nextUndeletedMap = mutableMap;
        for (long version = mutableMap.getVersion(); version >= oldestMap.getVersion(); version--) {
            final UnPurgedMap<K, V> undeletedMap = mapsNeedingPurging.get(version);
            if (undeletedMap != null) {
                nextUndeletedMap = undeletedMap;
            }
            nextUndeletedVersionMap.put(version, nextUndeletedMap.getVersion());
        }

        return nextUndeletedVersionMap;
    }

    /**
     * This class is used to attempt to purge a mutation if it is legal to do so.
     *
     * @param target
     * 		the mutation we are trying to purge (i.e. delete from the linked list of mutations)
     * @param nextUndeletedVersion
     * 		this is the version of the first undeleted map that meets or exceeds the target mutation's version
     * @param mapsNeedingPurging
     * 		all maps that are currently not purged (i.e. we have not done garbage collection on them yet).
     * 		This may be because the maps are not yet deleted, or because they have been deleted but we haven't gotten
     * 		around to purging their data yet.
     * @param currentMapVersion
     * 		the version of the map that is being purged
     * @param <K>
     * 		the type of the key
     * @param <V>
     * 		the type of the value
     */
    private record PurgeMutationHandler<K, V>(
            Mutation<V> target,
            long nextUndeletedVersion,
            Map<Long, UnPurgedMap<K, V>> mapsNeedingPurging,
            long currentMapVersion)
            implements BiFunction<K, Mutation<V>, Mutation<V>> {

        /**
         * Attempt to purge the target mutation.
         *
         * @param key
         * 		the key that points to the linked list of mutations
         * @param mutationHead
         * 		the first mutation in the linked list, this will be the most recent mutation added to the linked list
         * @return this method will return the mutation that should become the new head of the linked list, or null
         * 		if the linked list should be entirely removed from the data map
         */
        @Override
        public Mutation<V> apply(final K key, final Mutation<V> mutationHead) {
            requireNonNull(
                    mutationHead, "Mutation head must not be null, can't purge mutations if there are no mutations");

            final Mutation<V> next = target.getNext();
            requireNonNull(next, "Next should not be null. Mutation being purged is the latest mutation.");

            if (next.getVersion() <= nextUndeletedVersion) {
                // The next mutation is visible to the next undeleted map, meaning the target
                // mutation is unreachable and safe to purge.

                next.setPrevious(target.getPrevious());
                if (target.getPrevious() != null) {
                    target.getPrevious().setNext(next);
                }

            } else {
                // This mutation is currently visible to an undeleted map.
                // Retry purging when that map is deleted.
                if (!mapsNeedingPurging.get(nextUndeletedVersion).schedulePurging(key, target)) {
                    // This should be impossible
                    throw new IllegalStateException(("Unable to schedule purging for mutation with map version %d, "
                                    + "this should not be possible, since map version %d is currently "
                                    + "being purged and holds an exclusive lock.")
                            .formatted(nextUndeletedVersion, currentMapVersion));
                }
            }

            if (mutationHead.getValue() == null && mutationHead.getPrevious() == null) {
                // If the last remaining mutation is a deletion record then it's safe to remove this key entirely.
                return null;
            }
            return mutationHead;
        }
    }

    /**
     * Perform purging for a mutation, deleting it if it is no longer referenced
     * by any undeleted map.
     *
     * @param currentMapVersion
     * 		the version of the map that is being purged
     * @param purgingEvent
     * 		describes the mutation to purge
     * @param nextUndeletedVersionMap
     * 		a map of hypothetical mutation versions to the first version of an undeleted
     * 		map that has a version that is greater or equal to the mutation version
     */
    private void purgeMutation(
            final long currentMapVersion,
            final PurgingEvent<K, V> purgingEvent,
            final Map<Long, Long> nextUndeletedVersionMap) {

        // This is the mutation we want to purge, if possible.
        final Mutation<V> target = purgingEvent.mutation();

        // This is the first undeleted map version that meets or exceeds the target mutation's version.
        final long nextUndeletedVersion =
                nextUndeletedVersionMap.getOrDefault(target.getVersion(), oldestMap.getVersion());

        data.compute(
                purgingEvent.key(),
                new PurgeMutationHandler<>(target, nextUndeletedVersion, mapsNeedingPurging, currentMapVersion));
    }

    /**
     * Delete a map from the family.
     *
     * @param mapVersion
     * 		the version of the map that is being deleted.
     * 		If the mutable version is deleted then no new copies are permitted.
     */
    public void releaseMap(final long mapVersion) {
        try (final Locked locked = deletionLock.lock()) {
            if (mutableMap == null || mutableMap.getVersion() == mapVersion) {
                // Once the mutable copy has been released there is no point in doing any additional work.
                // Once the maps are no longer referenced by anything the JVM garbage collector will clean things up.
                mutableMap = null;
                return;
            }

            final UnPurgedMap<K, V> mapToDelete = mapsNeedingPurging.remove(mapVersion);

            if (mapToDelete == null) {
                // This should be impossible.
                throw new IllegalStateException("Map with version " + mapVersion + " does not exist");
            }

            mapToDelete.markAsPurged();

            // Remove the map from the list of undeleted maps.
            final UnPurgedMap<K, V> previous = mapToDelete.getPrevious();
            final UnPurgedMap<K, V> next = mapToDelete.getNext();
            if (previous != null) {
                previous.setNext(next);
            }
            if (next != null) {
                next.setPrevious(previous);
            }

            if (mapVersion == oldestMap.getVersion()) {
                oldestMap = oldestMap.getNext();
            }

            final Map<Long, Long> nextUndeletedVersionMap = buildNextUndeletedVersionMap();
            for (final PurgingEvent<K, V> event : mapToDelete) {
                purgeMutation(mapVersion, event, nextUndeletedVersionMap);
            }
        }
    }
}
