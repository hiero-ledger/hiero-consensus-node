// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

/**
 * A class that holds all the user readable names
 */
public abstract class PlatformStatNames {
    /** number of consensus transactions per second handled by ConsensusStateEventHandler.onHandleConsensusRound() */
    public static final String TRANSACTIONS_HANDLED_PER_SECOND = "transH_per_sec";
    /** number of consensus events in queue waiting to be handled */
    public static final String CONSENSUS_QUEUE_SIZE = "consEvents";
    /** number of preconsensus events in queue waiting to be handled */
    public static final String PRE_CONSENSUS_QUEUE_SIZE = "preConsEvents";
    /** the number of creators that have more than one tip at the start of each sync */
    public static final String MULTI_TIPS_PER_SYNC = "multiTips_per_sync";
    /** the number of tips per sync at the start of each sync */
    public static final String TIPS_PER_SYNC = "tips_per_sync";
    /** the ratio of rejected sync to accepted syncs over time. */
    public static final String REJECTED_SYNC_RATIO = "rejectedSyncRatio";

    public static final String TRANS_SUBMIT_MICROS = "avgTransSubmitMicros";
}
