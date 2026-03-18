// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.status.PlatformStatusAction;

/**
 * An action that is triggered when the platform is done replaying events from the preconsensus event stream.
 *
 * @param instant the instant at which the action was triggered
 */
public record DoneReplayingEventsAction(@NonNull Instant instant) implements PlatformStatusAction {}
