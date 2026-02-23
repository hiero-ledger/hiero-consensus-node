// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.utility.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proxy for a {@link VirtualMap} that maintains a separate map to track changes and validate data consistency.
 */
public class VirtualMapValidator {

    // VirtualMap instance to validate.
    private VirtualMap subject;
    // Reference map tracking current map values.
    private final Map<Bytes, Bytes> reference = new HashMap<>();
    // Snapshot of the current virtual map and reference values over time.
    private final List<Pair<VirtualMap, Map<Bytes, Bytes>>> snapshots = new ArrayList<>();
    // Per-key history of the value changes for debugging.
    private final Map<Bytes, List<Bytes>> history = new HashMap<>();

    public VirtualMapValidator(final VirtualMap subject) {
        this.subject = subject;
    }

    @SuppressWarnings("unchecked")
    public void put(final Bytes key, final Bytes value) {
        reference.put(key, value);
        subject.putBytes(key, value);
        if (!history.containsKey(key)) {
            history.put(key, new ArrayList<>());
        }
        history.get(key).add(value);
    }

    public void remove(final Bytes key) {
        subject.remove(key);
        if (reference.containsKey(key)) {
            reference.remove(key);
            if (!history.containsKey(key)) {
                history.put(key, new ArrayList<>());
            }
            history.get(key).add(null);
        }
    }

    public Bytes get(final Bytes key) {
        final Bytes expected = reference.get(key);
        final Bytes actual = subject.getBytes(key);
        if (expected != null && !expected.equals(actual)) {
            // Failed to match. Print out debug info.
            dumpHistory(key);
            System.out.printf("Get Actual: [%s]:  %s%n", key, subject.getBytes(key));
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
    public VirtualMap newCopy() {
        // Snapshot all references at time of copy
        final Map<Bytes, Bytes> referenceCopy = new HashMap<>();
        for (final Bytes key : reference.keySet()) {
            final Bytes snapshotValue = subject.getBytes(key);
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
        for (final Pair<VirtualMap, Map<Bytes, Bytes>> pair : snapshots) {
            verifyMatch(round, pair.left(), pair.right());
            round++;
        }
    }

    /**
     * Print history of value changes for the specified key.
     *
     * @param key the key to print change history of.
     */
    public void dumpHistory(final Bytes key) {
        System.out.printf("[History for %s]%n", key);
        for (final Bytes value : history.get(key)) {
            System.out.println(value);
        }
    }

    private void verifyMatch(final int round, final VirtualMap snapshot, final Map<Bytes, Bytes> reference) {
        assertEquals(reference.size(), snapshot.size());
        for (final Bytes key : reference.keySet()) {
            if (!reference.get(key).equals(snapshot.getBytes(key))) {
                // Failed to match. Print out debug info.
                System.out.printf(
                        "Failed on key %s for round %d. Expected %s. Actual %s%n",
                        key, round, reference.get(key), snapshot.getBytes(key));
                dumpHistory(key);
                fail("Snapshot failed to match expected reference.");
            }
        }
    }
}
