// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.crypto.Hash;

/**
 * Basic record object to carry information useful for signed state validation.
 *
 * @param round
 * 		the minimum round to be considered a valid state
 * @param consensusTimestamp
 * 		The consensus timestamp from an earlier state
 * @param rosterHash
 * 		The roster hash value for the current roster (mostly used for diagnostics).
 * @param consensusEventsRunningHash
 * 		The running hash of the consensus event hashes throughout history
 */
public record SignedStateValidationData(
        long round,
        @NonNull Instant consensusTimestamp,
        @Nullable Hash rosterHash,
        @NonNull Hash consensusEventsRunningHash) {

    public SignedStateValidationData(
            @NonNull final State that,
            @Nullable final Roster roster,
            @NonNull final PlatformStateFacade platformStateFacade) {
        this(
                platformStateFacade.roundOf(that),
                platformStateFacade.consensusTimestampOf(that),
                roster == null ? null : RosterUtils.hash(roster),
                platformStateFacade.legacyRunningEventHashOf(that));
    }

    /**
     * Informational method used for diagnostics.
     * This method constructs a {@link String} containing the critical attributes of this data object.
     * The original use is during reconnect to produce useful information sent to diagnostic event output.
     * @return a {@link String} containing the core data from this object, in human-readable form.
     */
    public String getInfoString() {
        return new StringBuilder()
                .append("Round = ")
                .append(round)
                .append(", consensus timestamp = ")
                .append(consensusTimestamp)
                .append(", consensus Events running hash = ")
                .append(consensusEventsRunningHash)
                .append(", roster hash = ")
                .append(rosterHash != null ? rosterHash : "not provided")
                .toString();
    }
}
