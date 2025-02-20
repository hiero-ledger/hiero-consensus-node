// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gui.hashgraph;

/**
 * Options for drawing the {@code HashgraphPicture}
 */
public interface HashgraphPictureOptions {
    /**
     * @return should the picture freeze and not change
     */
    boolean isPictureFrozen();

    /**
     * @return should the hashgraph be expanded
     */
    boolean isExpanded();

    /**
     * @return should round created be written for every event
     */
    boolean writeRoundCreated();

    /**
     * @return should round received be written for every event
     */
    boolean writeRoundReceived();

    /**
     * @return should consensus order be written for every event
     */
    boolean writeConsensusOrder();

    /**
     * @return should consensus timestamp be written for every event
     */
    boolean writeConsensusTimeStamp();

    /**
     * @return should the generation be written for every event
     */
    boolean writeGeneration();

    /**
     * @return should simple colors be used in the picture
     */
    boolean simpleColors();

    /**
     * @return the number of generations to display
     */
    int getNumGenerationsDisplay();

    /**
     * @return the first generation that should be displayed
     */
    long getStartGeneration();

    /**
     * @return should the latest events be displayed, ignores {@link #getStartGeneration()}
     */
    boolean displayLatestEvents();

    /**
     * When {@link #displayLatestEvents()} is true, this method will be called to notify which is the current starting
     * generation
     *
     * @param startGeneration
     * 		the first generation being displayed
     */
    void setStartGeneration(final long startGeneration);
}
