// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A marker is a named reference to a location / package / context in the code. It can be used to filter log messages at
 * runtime
 *
 * @param name   the name of the marker
 * @param previous the previous marker (if present)
 */
public record Marker(@NonNull String name, @Nullable Marker previous) {

    public Marker {
        Objects.requireNonNull(name, "name must not be null");
    }

    public Marker(@NonNull final String name) {
        this(name, null);
    }

    @NonNull
    public List<String> getAllMarkerNames() {
        if (previous != null) {
            final List<String> result = new ArrayList<>(previous.getAllMarkerNames());
            result.add(name);
            return Collections.unmodifiableList(result);
        } else {
            return List.of(name);
        }
    }
}
