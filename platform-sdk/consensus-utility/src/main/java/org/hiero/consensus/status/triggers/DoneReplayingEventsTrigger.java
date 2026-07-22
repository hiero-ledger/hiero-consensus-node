// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A trigger to indicate that the platform is done replaying events from the preconsensus event stream.
 *
 * @param instant the instant at which the trigger was created
 */
public record DoneReplayingEventsTrigger(@NonNull Instant instant) implements StatusMachineTrigger {}
