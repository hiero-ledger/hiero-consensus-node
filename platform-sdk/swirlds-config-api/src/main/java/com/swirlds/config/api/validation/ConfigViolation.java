// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.validation;

import java.io.Serializable;

/**
 * A violation that is based on a validation (see {@link ConfigValidator}) or a constraint (see
 * {@link ConfigPropertyConstraint}).
 */
public interface ConfigViolation extends Serializable {

    /**
     * Returns the name of the property that caused the violation.
     *
     * @return name of the property
     */
    String getPropertyName();

    /**
     * Returns the message of the violation.
     *
     * @return the message of the violation
     */
    String getMessage();

    /**
     * Returns the value of the property that caused the violation.
     *
     * @return the value of the property
     */
    String getPropertyValue();

    /**
     * Returns true if the property exists (is defined by a {@link com.swirlds.config.api.source.ConfigSource}), false
     * otherwise.
     *
     * @return true if the property exists
     */
    boolean propertyExists();
}
