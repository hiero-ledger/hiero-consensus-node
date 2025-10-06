// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph.internal;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.platform.gui.BranchedEventMetadata;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata that is used to aid in drawing a {@code HashgraphPicture}
 */
public class PictureMetadata {
    /**
     * the gap between left side of screen and leftmost column
     * is marginFraction times the gap between columns (and similarly for right side)
     */
    private static final double MARGIN_FRACTION = 0.5;

    private final RosterMetadata rosterMetadata;
    private final int ymax;
    private final int ymin;
    private final int width;
    private final double r;
    private final long minGen;
    private final long maxGen;

    private final HashgraphGuiSource hashgraphSource;
    private final Map<Long, Map<Long, GenerationCoordinates>> nodeIdToGenerationToCoordinates;

    /**
     *
     * @param fm font metrics to use for visualisation
     * @param pictureDimension the dimension of the UI component that will be used
     * @param rosterMetadata metadata for the roster
     * @param events the events to be displayed
     * @param hashgraphSource the needed information for visualisation from the hashgraph to use as a source
     * @param nodeIdToGenerationToCoordinates map collecting coordinates info for branched events with the
     * same generation for each forking node
     *
     */
    public PictureMetadata(
            final FontMetrics fm,
            final Dimension pictureDimension,
            final RosterMetadata rosterMetadata,
            final List<EventImpl> events,
            final HashgraphGuiSource hashgraphSource,
            final Map<Long, Map<Long, GenerationCoordinates>> nodeIdToGenerationToCoordinates) {
        this.rosterMetadata = rosterMetadata;
        this.hashgraphSource = hashgraphSource;
        this.nodeIdToGenerationToCoordinates = nodeIdToGenerationToCoordinates;
        final int fa = fm.getMaxAscent();
        final int fd = fm.getMaxDescent();
        final int textLineHeight = fa + fd;

        width = (int) pictureDimension.getWidth();

        // where to draw next in the window, and the font height
        final int height1 = 0; // text area at the top
        final int height2 = (int) (pictureDimension.getHeight() - height1); // the main display, below the text
        ymin = (int) Math.round(height1 + 0.025 * height2);
        ymax = (int) Math.round(height1 + 0.975 * height2) - textLineHeight;

        long minGenTmp = Long.MAX_VALUE;
        long maxGenTmp = Long.MIN_VALUE;
        for (final EventImpl event : events) {
            minGenTmp = Math.min(minGenTmp, event.getNGen());
            maxGenTmp = Math.max(maxGenTmp, event.getNGen());
        }
        maxGenTmp = Math.max(maxGenTmp, minGenTmp + 2);
        minGen = minGenTmp;
        maxGen = maxGenTmp;

        final int n = rosterMetadata.getNumMembers() + 1;
        final double gens = maxGen - minGen;
        final double dy = (ymax - ymin) * (gens - 1) / gens;
        r = Math.min(width / n / 4, dy / gens / 2);
    }

    /**
     * @return the gap between columns
     */
    public int getGapBetweenColumns() {
        return (int) (width / (rosterMetadata.getNumColumns() - 1 + 2 * MARGIN_FRACTION));
    }

    /**
     * @return gap between leftmost column and left edge (and similar on right)
     */
    public int getSideGap() {
        return (int) (getGapBetweenColumns() * MARGIN_FRACTION);
    }

    /** find x position on the screen for event e2 which has an other-parent of e1 (or null if none) */
    public int xpos(final EventImpl e1, final EventImpl e2) {
        // the gap between left side of screen and leftmost column
        // is marginFraction times the gap between columns (and similarly for right side)
        final double marginFraction = 0.5;
        // gap between columns
        final int betweenGap = (int) (width / (rosterMetadata.getNumColumns() - 1 + 2 * marginFraction));
        // gap between leftmost column and left edge (and similar on right)
        final int sideGap = (int) (betweenGap * marginFraction);

        // find the column for e2 next to the column for e1
        int xPos = sideGap + rosterMetadata.mems2col(e1, e2) * betweenGap;

        final GossipEvent e2GossipEvent = e2.getBaseEvent().getGossipEvent();

        // check if we have a branched event
        if (hashgraphSource.getEventStorage().getBranchedEventsMetadata().containsKey(e2GossipEvent)) {
            return calculateXPosForBranchedEvent(e2, xPos);
        }

        return xPos;
    }

    /**
     * find y position on the screen for an event
     */
    public int ypos(final EventImpl event) {
        return (event == null) ? -100 : (int) (ymax - r * (1 + 2 * (event.getNGen() - minGen)));
    }

    /**
     * @return the diameter of a circle representing an event
     */
    public int getD() {
        return (int) (2 * r);
    }

    public int getYmax() {
        return ymax;
    }

    public int getYmin() {
        return ymin;
    }

    /**
     * @return the minimum generation being displayed
     */
    public long getMinGen() {
        return minGen;
    }

    /**
     * Calculate the X coordinate for an event that is branched respecting the existing coordinates for the
     * other events in the same branch and generation
     *
     * @return the calculated X coordinate for a branched event
     */
    private int calculateXPosForBranchedEvent(@NonNull final EventImpl event, final int currentXPos) {
        final GossipEvent gossipEvent = event.getBaseEvent().getGossipEvent();

        final BranchedEventMetadata branchedEventMetadata =
                hashgraphSource.getEventStorage().getBranchedEventsMetadata().get(gossipEvent);

        final Map<Long, GenerationCoordinates> generationToXCoordinates =
                nodeIdToGenerationToCoordinates.get(event.getCreatorId().id());

        final GenerationCoordinates coordinatesForGeneration = generationToXCoordinates.computeIfAbsent(
                branchedEventMetadata.getGeneration(), b -> new GenerationCoordinates());

        Map<GossipEvent, Integer> xCoordinates = coordinatesForGeneration.getXCoordinates();

        int calculatedXPos;
        if (xCoordinates != null) {
            // event still does not have X coordinate
            if (!xCoordinates.containsKey(gossipEvent)) {
                final int maxXCoordinateForGeneration = coordinatesForGeneration.getRightMostX();

                calculatedXPos = maxXCoordinateForGeneration + (int) (1.5 * r);

                xCoordinates.put(gossipEvent, calculatedXPos);
                // associate the current event's X coordinate to be the far right value for the generation this
                // event belongs to
                coordinatesForGeneration.setRightMostX(calculatedXPos);
            } else {
                // event has assigned X coordinate, so just assign it
                calculatedXPos = xCoordinates.get(gossipEvent);
            }
        } else {
            // assign the first X coordinate for the current generation by moving the current X position to the left,
            // so that branched events appear to be offset from creator's node column
            calculatedXPos = currentXPos - (int) (3 * r / 4);

            xCoordinates = new HashMap<>();
            xCoordinates.put(gossipEvent, calculatedXPos);
            coordinatesForGeneration.setXCoordinates(xCoordinates);
            coordinatesForGeneration.setRightMostX(calculatedXPos);
        }

        return calculatedXPos;
    }
}
