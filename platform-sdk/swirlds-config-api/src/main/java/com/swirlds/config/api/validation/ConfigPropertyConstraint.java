// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A constraint that validates a specific property.
 *
 * @param <T> value type of the property
 */
@FunctionalInterface
public interface ConfigPropertyConstraint<T> {

    /**
     * Returns a violation if the check of the given property fails.
     *
     * @param metadata metadata of the property that should be checked
     * @return a violation if the check fails or null
     */
    @Nullable
    ConfigViolation check(@NonNull PropertyMetadata<T> metadata);
}
