/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.signed;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.crypto.Hash;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

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
