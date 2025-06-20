// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss;

import com.swirlds.platform.scratchpad.ScratchpadType;

/**
 * Describes the data held in the ISS scratchpad.
 */
public enum IssScratchpad implements ScratchpadType {
    /**
     * The round number of the most recently observed ISS, or null if this node has never observed an ISS.
     */
    LAST_ISS_ROUND(0);

    // FUTURE WORK: store data that allows us to detect when we have attempted to restart from the same state snapshot
    // multiple times without resolving the ISS, and potentially allow nodes to delete some of their states in order
    // to resolve the ISS.

    private final int fieldId;

    /**
     * Constructor.
     *
     * @param fieldId the field ID
     */
    IssScratchpad(final int fieldId) {
        this.fieldId = fieldId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getFieldId() {
        return fieldId;
    }
}
