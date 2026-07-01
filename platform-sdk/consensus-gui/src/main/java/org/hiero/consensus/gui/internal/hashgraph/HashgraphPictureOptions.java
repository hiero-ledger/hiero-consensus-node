// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.internal.hashgraph;

/**
 * Options for drawing the {@code HashgraphPicture}
 */
public interface HashgraphPictureOptions {
    /**
     * @return should the picture freeze and not change
     */
    boolean isPictureFrozen();

    /**
     * @return should round created be written for every event
     */
    boolean writeRoundCreated();

    /**
     * @return should the vote be written
     */
    boolean writeVote();

    /**
     * @return should the event hash be written for every event
     */
    boolean writeEventHash();

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
     * @return should the sequence number be written for every event
     */
    boolean writeSeqNum();

    /**
     * @return should the birth round be written for every event
     */
    boolean writeBirthRound();

    /**
     * @return should branch numbers be written for every branched event (if any)
     */
    boolean writeBranches();

    /**
     * @return should the DeGen be written for every event
     */
    boolean writeDeGen();

    /**
     * @return should simple colors be used in the picture
     */
    boolean simpleColors();

    /**
     * @return the number of events to display
     */
    int getNumEventsDisplay();

    /**
     * @return the first sequence number that should be displayed
     */
    long getStartSequenceNumber();

    /**
     * @return should the latest events be displayed, ignores {@link #getStartSequenceNumber()}
     */
    boolean displayLatestEvents();

    /**
     * When {@link #displayLatestEvents()} is true, this method will be called to notify which is the current starting
     * generation
     *
     * @param startSequenceNumber
     * 		the first sequence number being displayed
     */
    void setStartSequenceNumber(final long startSequenceNumber);
}
