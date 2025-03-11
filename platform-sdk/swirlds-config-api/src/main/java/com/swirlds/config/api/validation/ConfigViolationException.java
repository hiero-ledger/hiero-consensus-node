// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An exceptions that wraps collection of violations.
 */
public class ConfigViolationException extends IllegalStateException {

    private final List<ConfigViolation> violations;

    /**
     * Creates a new instance based on violations.
     *
     * @param message    message of the exception
     * @param violations the violations
     */
    public ConfigViolationException(@Nullable final String message, @NonNull final List<ConfigViolation> violations) {
        super(message);
        Objects.requireNonNull(violations, "violations should not be null");
        this.violations = Collections.unmodifiableList(violations);
    }

    /**
     * Returns the immutable list of violations.
     *
     * @return the list of violations
     */
    public List<ConfigViolation> getViolations() {
        return violations;
    }
}
