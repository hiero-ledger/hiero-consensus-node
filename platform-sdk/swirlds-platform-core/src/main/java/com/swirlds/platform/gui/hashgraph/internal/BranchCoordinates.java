// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.hedera.hapi.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * Class holding coordinated related data for a specific branch for a given node.
 *
 */
public class BranchCoordinates {

    // the Y coordinate for the whole branch
    private int y;
    // the first assigned X value for an event in this branch, it's needed when events are reloaded and
    // they need to be drawn from the leftmost beginning of the branch
    private int farMostLeftX;
    // the X coordinates of all events in this branch, some of them might not be displayed, but are needed if
    // the user decides to change the display generation range
    private Map<GossipEvent, Integer> allXCoordinates;
    // the X coordinates of all events in this branch that are inside the currently displayed generation range
    private Map<GossipEvent, Integer> insideGenerationRangeXCoordinates;

    @NonNull
    public int getY() {
        return y;
    }

    @NonNull
    public int getFarMostLeftX() {
        return farMostLeftX;
    }

    @Nullable
    public Map<GossipEvent, Integer> getAllXCoordinates() {
        return allXCoordinates;
    }

    @Nullable
    public Map<GossipEvent, Integer> getInsideGenerationRangeXCoordinates() {
        return insideGenerationRangeXCoordinates;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setFarMostLeftX(int farMostLeftX) {
        this.farMostLeftX = farMostLeftX;
    }

    public void setEventToXCoordinatesForAllBranchedEvents(@NonNull final Map<GossipEvent, Integer> allXCoordinates) {
        this.allXCoordinates = allXCoordinates;
    }

    public void setInsideGenerationRangeXCoordinates(
            @NonNull final Map<GossipEvent, Integer> insideGenerationRangeXCoordinates) {
        this.insideGenerationRangeXCoordinates = insideGenerationRangeXCoordinates;
    }
}
