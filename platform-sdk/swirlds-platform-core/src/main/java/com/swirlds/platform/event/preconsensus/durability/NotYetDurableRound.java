// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus.durability;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A consensus round that is not yet durable.
 *
 * @param round        the consensus round that is not yet durable
 * @param receivedTime the time at which the round was received by the {@link RoundDurabilityBuffer} instance
 */
record NotYetDurableRound(@NonNull ConsensusRound round, @NonNull Instant receivedTime) {}
