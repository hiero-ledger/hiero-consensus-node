// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap;

import com.swirlds.common.FastCopyable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * This class is used to encapsulate an {@link FCHashMap} and a series of copies of that map.
 */
public class FCHashMapSeries<K, V extends FastCopyable> implements Iterable<FCHashMap<K, V>> {

    private final Map<Long, FCHashMap<K, V>> copies;
    private final List<Long> copiesEligibleForDeletion;

    private long latestVersion;

    /**
     * Create a new series of FCHashMaps with a single mutable copy.
     */
    public FCHashMapSeries() {
        copies = new HashMap<>();
        final FCHashMap<K, V> map = new FCHashMap<>();
        latestVersion = map.getVersion();
        copies.put(map.getVersion(), map);

        copiesEligibleForDeletion = new ArrayList<>();
    }

    /**
     * Get a copy at a given version.
     *
     * @throws IllegalStateException
     * 		if the given version does not exist.
     */
    public FCHashMap<K, V> get(final long version) {
        final FCHashMap<K, V> map = copies.get(version);
        if (map == null) {
            throw new IllegalStateException("No copy at version " + version + " exists");
        }
        return map;
    }

    /**
     * Get an iterator over all undeleted copies. No order guarantees are provided.
     */
    @Override
    public Iterator<FCHashMap<K, V>> iterator() {
        return copies.values().iterator();
    }

    /**
     * Get the latest (mutable) copy of the map.
     */
    public FCHashMap<K, V> getLatest() {
        if (copies.size() == 0) {
            throw new IllegalStateException("all copies have been released");
        }
        return get(latestVersion);
    }

    /**
     * Make a fast copy of the map.
     */
    public void copy() {
        final FCHashMap<K, V> previousMutableCopy = getLatest();
        final FCHashMap<K, V> copy = previousMutableCopy.copy();
        copiesEligibleForDeletion.add(previousMutableCopy.getVersion());
        latestVersion = copy.getVersion();
        copies.put(copy.getVersion(), copy);
    }

    /**
     * Get the number of copies, including the mutable copy.
     */
    public int getNumberOfCopies() {
        return copies.size();
    }

    /**
     * Delete a random copy. Will not delete the mutable copy.
     *
     * @param random
     * 		a source of randomness
     */
    public void delete(final Random random) {
        if (copiesEligibleForDeletion.size() > 1) {
            delete(copiesEligibleForDeletion.get(random.nextInt(copiesEligibleForDeletion.size() - 1)));
        } else {
            delete(copiesEligibleForDeletion.get(0));
        }
    }

    /**
     * Delete a given version of the map. Can not be used ot delete the latest version.
     */
    public void delete(final long version) {
        if (version == latestVersion) {
            throw new IllegalStateException("this method can not be used to delete the mutable copy");
        }
        get(version).release();

        final Iterator<Long> versionIterator = copiesEligibleForDeletion.iterator();
        while (versionIterator.hasNext()) {
            if (versionIterator.next() == version) {
                versionIterator.remove();
                break;
            }
        }
        copies.remove(version);
    }

    /**
     * Delete the mutable copy of the map.
     *
     * @throws IllegalStateException
     * 		if there are any immutable copies of the map that haven't yet been deleted
     */
    public void deleteMutableCopy() {
        if (!copiesEligibleForDeletion.isEmpty()) {
            throw new IllegalStateException("all immutable copies must first be deleted");
        }
        get(latestVersion).release();
        copies.remove(latestVersion);
    }
}
