// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.platform.consensus.ConsensusUtils.calcMinTimestampForNextEvent;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.MIN_TRANS_TIMESTAMP_INCR_NANOS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConsensusImplTest {

    @Test
    void calcMinTimestampForNextEventTest() {
        final Instant lastTransTimestamp = Instant.now();
        final Instant calculatedTimestamp = calcMinTimestampForNextEvent(lastTransTimestamp);
        final Instant lastTransTimestampPlusIncr = lastTransTimestamp.plusNanos(MIN_TRANS_TIMESTAMP_INCR_NANOS);
        assertFalse(
                calculatedTimestamp.isBefore(lastTransTimestampPlusIncr),
                "the timestamp should not be less than lastTransTimestamp + Settings.minTransTimestampIncrNanos");

        final int calculatedTimestampNanos = calculatedTimestamp.getNano();
        assertTrue(
                calculatedTimestampNanos % MIN_TRANS_TIMESTAMP_INCR_NANOS == 0
                        && (calculatedTimestampNanos - lastTransTimestampPlusIncr.getNano())
                                        / MIN_TRANS_TIMESTAMP_INCR_NANOS
                                < 1,
                "the timestamp should be the nearest multiple of minTransTimestampIncr");
    }
}
