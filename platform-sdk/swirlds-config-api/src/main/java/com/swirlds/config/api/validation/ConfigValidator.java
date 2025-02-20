// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;

/**
 * A validator to validate the configuration at initialization.
 */
@FunctionalInterface
public interface ConfigValidator {

    /**
     * Returns a {@link Stream} of possible violations as a result of the validation. If no violation happens the stream
     * will be empty
     *
     * @param configuration the configuration
     * @return the violations
     */
    @NonNull
    Stream<ConfigViolation> validate(@NonNull Configuration configuration);
}
