// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import com.swirlds.demo.platform.HotspotConfiguration;
import com.swirlds.demo.platform.PAYLOAD_TYPE;
import java.io.Serializable;

public class FCMSequential implements Serializable {

    private PAYLOAD_TYPE sequentialType;
    private int sequentialAmount;
    private int sequentialSize;

    /**
     * An optional configuration for a hotspot. Not all {@link PAYLOAD_TYPE}s treat hotspots in the same way.
     */
    private HotspotConfiguration hotspot;

    public PAYLOAD_TYPE getSequentialType() {
        return sequentialType;
    }

    public void setSequentialType(PAYLOAD_TYPE sequentialType) {
        this.sequentialType = sequentialType;
    }

    public int getSequentialAmount() {
        return sequentialAmount;
    }

    public void setSequentialAmount(int sequentialAmount) {
        this.sequentialAmount = sequentialAmount;
    }

    public int getSequentialSize() {
        return sequentialSize;
    }

    public void setSequentialSize(int sequentialSize) {
        this.sequentialSize = sequentialSize;
    }

    /**
     * Get the hotspot configuration for this group of transactions, or null if there is no configured hotspot.
     */
    public HotspotConfiguration getHotspot() {
        return hotspot;
    }

    /**
     * Set the hotspot configuration for this group of transactions.
     */
    public void setHotspot(final HotspotConfiguration hotspot) {
        this.hotspot = hotspot;
    }
}
