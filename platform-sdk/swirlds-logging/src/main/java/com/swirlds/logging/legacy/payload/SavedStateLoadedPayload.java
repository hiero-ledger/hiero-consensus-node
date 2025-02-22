// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Objects;

/**
 * This payload is logged when the platform loads a saved state from disk.
 */
public class SavedStateLoadedPayload extends AbstractLogPayload {

    private long round;
    private Instant consensusTimestamp;

    public SavedStateLoadedPayload(final long round, @NonNull final Instant consensusTimestamp) {
        super("Platform has loaded a saved state");
        this.round = round;
        this.consensusTimestamp = Objects.requireNonNull(consensusTimestamp);
    }

    public long getRound() {
        return round;
    }

    public void setRound(final long round) {
        this.round = round;
    }

    public Instant getConsensusTimestamp() {
        return consensusTimestamp;
    }

    public void setConsensusTimestamp(final Instant consensusTimestamp) {
        this.consensusTimestamp = consensusTimestamp;
    }
}
