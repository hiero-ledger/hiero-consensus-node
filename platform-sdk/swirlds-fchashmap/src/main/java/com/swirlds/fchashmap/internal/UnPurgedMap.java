// SPDX-License-Identifier: Apache-2.0
package com.swirlds.fchashmap.internal;

import com.swirlds.fchashmap.FCHashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Tracks an {@link FCHashMap} that has yet to be purged.
 */
public class UnPurgedMap<K, V> implements Iterable<PurgingEvent<K, V>> {

    /**
     * The version of the map.
     */
    private final long version;

    // The variables previous and next are read and written on multiple threads -- and this is thread safe.
    //
    // The thread that is releasing map copies and the thread that is making new map copies both write to these
    // variables. But the thread releasing map copies will NEVER write to the mutable map's next field or the new
    // copy's previous field, and the thread making new copies will ONLY write to the mutable map's next field
    // and to the new copy's previous field.
    //
    // The thread that is modifying the mutable copy will read the previous field but not the next field. It does this
    // when it is iterating backwards over all un-purged copies to find a copy willing to schedule garbage collection.
    // This is not permitted to happen in parallel with a copy() operation, but it is permitted to happen in parallel
    // with a garbage collection operation. Garbage collection may remove copies from this linked list, but will
    // never add copies. In java, references are atomic. The modifying thread has a race condition between
    // seeing the link that is purged and not seeing the link that is purged. But this is not a problem because
    // scheduling of garbage collection is protected by the synchronization between the methods markAsPurged()
    // and scheduleGarbageCollectionForKey(). If the modifying thread observers a purged link, it will ignore
    // that link and continue iterating backwards. The net effect is that both branches in the race condition
    // are indistinguishable.

    /**
     * The previous un-purged map.
     */
    private UnPurgedMap<K, V> previous;

    /**
     * The next un-purged map.
     */
    private UnPurgedMap<K, V> next;

    /**
     * Keys that require garbage collection work when this copy of the map is purged.
     */
    private final List<PurgingEvent<K, V>> purgingEvents = new LinkedList<>();

    /**
     * True if this map has been purged, or is in the process of being purged.
     */
    private boolean purged;

    /**
     * Start tracking an un-purged map.
     *
     * @param version
     * 		the version of the map
     */
    public UnPurgedMap(final long version) {
        this.version = version;
    }

    /**
     * Set the previous map that needs garbage collection,
     * or null if this is the oldest map that needs garbage collection.
     */
    public void setPrevious(final UnPurgedMap<K, V> previous) {
        this.previous = previous;
    }

    /**
     * Get the previous map that needs garbage collection,
     * or null if this is the oldest map that needs garbage collection.
     */
    public UnPurgedMap<K, V> getPrevious() {
        return previous;
    }

    /**
     * Set the next map that needs garbage collection,
     * or null if this is the newest map that needs garbage collection.
     */
    public UnPurgedMap<K, V> getNext() {
        return next;
    }

    /**
     * Get the next map that needs garbage collection,
     *
     * @param next
     * 		the next map that needs garbage collection
     */
    public void setNext(final UnPurgedMap<K, V> next) {
        this.next = next;
    }

    /**
     * The version of the map.
     */
    public long getVersion() {
        return version;
    }

    /**
     * Schedule garbage collection.
     *
     * @param key
     * 		the key that requires garbage collection
     * @param mutation
     * 		the mutation that needs to be garbage collected
     * @return true if this copy will eventually garbage collect the key, false if this copy will not accept the key.
     * 		It's possible for a copy to not accept a key if it is being concurrently purged on another thread.
     */
    public synchronized boolean schedulePurging(final K key, final Mutation<V> mutation) {
        if (purged) {
            return false;
        }
        purgingEvents.add(new PurgingEvent<>(key, mutation));
        return true;
    }

    /**
     * Mark this map as having been purged.
     */
    public synchronized void markAsPurged() {
        purged = true;
    }

    /**
     * Get an iterator over the garbage collection events.
     */
    @Override
    public Iterator<PurgingEvent<K, V>> iterator() {
        return purgingEvents.iterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("version ").append(version).append(", purge keys = ");
        for (final PurgingEvent<K, V> event : purgingEvents) {
            sb.append(event).append(" ");
        }
        return sb.toString();
    }
}
