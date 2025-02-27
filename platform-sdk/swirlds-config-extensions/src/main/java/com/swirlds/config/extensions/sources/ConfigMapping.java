// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.sources;

import com.swirlds.base.ArgumentUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Represents a mapping between an original name and a mapped name. This class is used to hold the mapping configuration
 * between an original name and a mapped name.
 *
 * @param mappedName   new property name
 * @param originalName original property name
 */
public record ConfigMapping(@NonNull String mappedName, @NonNull String originalName) {

    /**
     * Creates a new {@code ConfigMapping}.
     *
     * @param mappedName   new property name
     * @param originalName original property name
     * @throws IllegalArgumentException If {@code mappedName} and {@code originalName} are equal
     */
    public ConfigMapping {
        ArgumentUtils.throwArgBlank(mappedName, "mappedName");
        ArgumentUtils.throwArgBlank(originalName, "originalName");
        if (Objects.equals(originalName, mappedName)) {
            throw new IllegalArgumentException(
                    "originalName and mappedName are the same (%s)! Will not create an mappedName"
                            .formatted(mappedName));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "'" + mappedName + "'<->'" + originalName + "'";
    }
}
