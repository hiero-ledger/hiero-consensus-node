// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.freeze;

public class FreezeConfig {
    /** The start freeze time would be after this many minutes since current test run*/
    int startFreezeAfterMin;

    public int getStartFreezeAfterMin() {
        return startFreezeAfterMin;
    }

    public void setStartFreezeAfterMin(int startFreezeAfterMin) {
        this.startFreezeAfterMin = startFreezeAfterMin;
    }
}
