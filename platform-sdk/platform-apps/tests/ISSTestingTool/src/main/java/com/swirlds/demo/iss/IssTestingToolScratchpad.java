// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.iss;

import com.swirlds.platform.scratchpad.ScratchpadType;

/**
 * Types of data stored in the scratch pad by the ISS Testing Tool.
 */
public enum IssTestingToolScratchpad implements ScratchpadType {

    /** Data about ISSs that were provoked */
    PROVOKED_ISS;

    @Override
    public int getFieldId() {
        return 1;
    }
}
