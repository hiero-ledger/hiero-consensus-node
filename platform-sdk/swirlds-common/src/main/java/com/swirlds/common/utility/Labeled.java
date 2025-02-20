// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

/**
 * An object with a label.
 */
@FunctionalInterface
public interface Labeled {

    /**
     * The maximum permitted character length of a label.
     */
    int MAX_LABEL_LENGTH = 1024;

    /**
     * Get the label associated with this object.
     *
     * @return this object's label
     */
    String getLabel();
}
