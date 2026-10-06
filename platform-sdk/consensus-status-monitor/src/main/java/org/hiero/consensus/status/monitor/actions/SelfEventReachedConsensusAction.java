// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.monitor.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.EventOrigin;

/**
 * An action that is triggered when the platform observes a self event reaching consensus.
 *
 * @param wallClockTime the wall clock time when this action was triggered
 * @param hasRuntimeSelfEvent {@code true} iff the triggering round contains at least one self
 *                            event with origin {@link EventOrigin#RUNTIME}. Consumers relying
 *                            on this signal for event-creation liveness must treat
 *                            {@code false} as "no runtime-created self event reached
 *                            consensus in this round" — self events replayed from local
 *                            storage or received via gossip do not qualify.
 */
public record SelfEventReachedConsensusAction(@NonNull Instant wallClockTime, boolean hasRuntimeSelfEvent)
        implements PlatformStatusAction {}
