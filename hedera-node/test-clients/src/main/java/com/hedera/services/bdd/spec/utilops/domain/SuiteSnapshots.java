// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.domain;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * A POJO for Jackson to use in storing all the {@link RecordSnapshot}'s for a given
 * {@link com.hedera.services.bdd.suites.HapiSuite}.
 */
public class SuiteSnapshots {
    /**
     * Maps from the name of a spec to its {@link RecordSnapshot}.
     */
    private Map<String, RecordSnapshot> specSnapshots = new HashMap<>();

    public Map<String, RecordSnapshot> getSpecSnapshots() {
        return specSnapshots;
    }

    public void setSpecSnapshots(@NonNull final Map<String, RecordSnapshot> specSnapshots) {
        this.specSnapshots = requireNonNull(specSnapshots);
    }

    /**
     * Adds a {@link RecordSnapshot} to the map of snapshots.
     *
     * @param name The name of the spec
     * @param snapshot The snapshot to add
     */
    public void addSnapshot(@NonNull final String name, @NonNull final RecordSnapshot snapshot) {
        requireNonNull(name);
        requireNonNull(snapshot);
        specSnapshots.put(name, snapshot);
    }

    /**
     * Gets the {@link RecordSnapshot} for the given spec name, or null if none exists.
     *
     * @param name the name of the spec
     * @return the snapshot, or null if none exists
     */
    public @Nullable RecordSnapshot getSnapshot(@NonNull final String name) {
        requireNonNull(name);
        return specSnapshots.get(name);
    }
}
