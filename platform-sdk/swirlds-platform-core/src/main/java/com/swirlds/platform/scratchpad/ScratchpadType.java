// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.scratchpad;

/**
 * Defines a {@link Scratchpad} type. Implementations must be enums.
 */
public interface ScratchpadType {

    /**
     * Get the field ID for this scratchpad type. Must be unique within this type.
     *
     * @return the field ID
     */
    int getFieldId();
}
