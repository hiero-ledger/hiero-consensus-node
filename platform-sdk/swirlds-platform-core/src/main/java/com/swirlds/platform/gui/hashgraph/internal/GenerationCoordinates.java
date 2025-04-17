// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * Class holding X coordinates for branched events with a specific generation for a given node.
 * Note that it's normal the branch to contain events with different generations.
 */
public class GenerationCoordinates {

    // the X coordinates of all branched events with the same generation
    private Map<GossipEvent, Integer> xCoordinates;
    // the X coordinate of the most right branched event for a given generation
    private Integer rightMostX = 0;

    /**
     * Return the X coordinates of all branched events with the same generation.
     *
     * @return the coordinates
     */
    public Map<GossipEvent, Integer> getXCoordinates() {
        return xCoordinates;
    }

    /**
     * Set updated X coordinates of all branched events with the same generation.
     *
     * @param xCoordinates the coordinates to set
     */
    public void setXCoordinates(@NonNull final Map<GossipEvent, Integer> xCoordinates) {
        this.xCoordinates = xCoordinates;
    }

    /**
     * Return the X coordinate of the most right branched event for a given generation.
     *
     * @return the coordinate
     */
    public Integer getRightMostX() {
        return rightMostX;
    }

    /**
     * Set the X coordinate of the most right branched event for a given generation.
     *
     * @param rightMostX the coordinate to set
     */
    public void setRightMostX(@NonNull final Integer rightMostX) {
        this.rightMostX = rightMostX;
    }
}
