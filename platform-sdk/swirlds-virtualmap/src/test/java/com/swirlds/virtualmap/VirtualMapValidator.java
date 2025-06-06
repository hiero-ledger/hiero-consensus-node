// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.utility.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxy for a {@link VirtualMap} that maintains a separate map to track changes and validate data consistency.
 *
 * @param <K> type of key -- must extend {@link VirtualKey}
 * @param <V> tyoe of value -- must extend {@link VirtualValue}
 */
public class VirtualMapValidator<K extends VirtualKey, V extends VirtualValue> {

    // VirtualMap instance to validate.
    private VirtualMap<K, V> subject;
    // Reference map tracking current map values.
    private final Map<K, V> reference = new HashMap<>();
    // Snapshot of the current virtual map and reference values over time.
    private final List<Pair<VirtualMap<K, V>, Map<K, V>>> snapshots = new ArrayList<>();
    // Per-key history of the value changes for debugging.
    private final Map<K, List<V>> history = new HashMap<>();

    public VirtualMapValidator(final VirtualMap<K, V> subject) {
        this.subject = subject;
    }

    @SuppressWarnings("unchecked")
    public void put(final K key, final V value) {
        reference.put(key, value);
        subject.put(key, value);
        if (!history.containsKey(key)) {
            history.put(key, new ArrayList<>());
        }
        history.get(key).add((V) value.asReadOnly());
    }

    public void remove(final K key) {
        subject.remove(key);
        if (reference.containsKey(key)) {
            reference.remove(key);
            if (!history.containsKey(key)) {
                history.put(key, new ArrayList<>());
            }
            history.get(key).add(null);
        }
    }

    public V get(final K key) {
        final V expected = reference.get(key);
        final V actual = subject.get(key);
        if (expected != null && !expected.equals(actual)) {
            // Failed to match. Print out debug info.
            dumpHistory(key);
            System.out.printf("Get Actual: [%s]:  %s%n", key, subject.get(key));
        }
        assertEquals(expected, actual);
        return actual;
    }

    /**
     * Create a new copy of the virtual map and update the subject to the new copy.
     *
     * <p>This call will also trigger a snapshot of the reference and virtualmap values to be stored.</p>
     *
     * @return reference to the VirtualMap copy.
     */
    public VirtualMap<K, V> newCopy() {
        // Snapshot all references at time of copy
        final Map<K, V> referenceCopy = new HashMap<>();
        for (final K key : reference.keySet()) {
            @SuppressWarnings("unchecked")
            final V snapshotValue = (V) subject.get(key).asReadOnly();
            referenceCopy.put(key, snapshotValue);
            if (!history.containsKey(key)) {
                history.put(key, new ArrayList<>());
            }
            history.get(key).add(null);
            history.get(key).add(snapshotValue);
        }
        snapshots.add(Pair.of(subject, referenceCopy));

        this.subject = subject.copy();
        return subject;
    }

    /**
     * Verify that each of the snapshots still match with the tracked reference.
     */
    public void validate() {
        int round = 0;
        for (final Pair<VirtualMap<K, V>, Map<K, V>> pair : snapshots) {
            verifyMatch(round, pair.left(), pair.right());
            round++;
        }
    }

    /**
     * Print history of value changes for the specified key.
     *
     * @param key the key to print change history of.
     */
    public void dumpHistory(final K key) {
        System.out.printf("[History for %s]%n", key);
        for (final V value : history.get(key)) {
            System.out.println(value);
        }
    }

    private void verifyMatch(final int round, final VirtualMap<K, V> snapshot, final Map<K, V> reference) {
        assertEquals(reference.size(), snapshot.size());
        for (final K key : reference.keySet()) {
            if (!reference.get(key).equals(snapshot.get(key))) {
                // Failed to match. Print out debug info.
                System.out.printf(
                        "Failed on key %s for round %d. Expected %s. Actual %s%n",
                        key, round, reference.get(key), snapshot.get(key));
                dumpHistory(key);
                fail("Snapshot failed to match expected reference.");
            }
        }
    }
}
