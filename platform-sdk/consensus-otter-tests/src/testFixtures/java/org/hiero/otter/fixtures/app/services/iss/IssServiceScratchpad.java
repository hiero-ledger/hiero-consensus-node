// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.iss;

import com.swirlds.platform.scratchpad.ScratchpadType;

/**
 * Types of data stored in the scratch pad by the ISS Service.
 */
public enum IssServiceScratchpad implements ScratchpadType {

    /** Data about ISSs that were provoked */
    PROVOKED_ISS;

    @Override
    public int getFieldId() {
        return 1;
    }
}
