// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * An action that is triggered when the platform observes a self event reaching consensus.
 *
 * @param wallClockTime the wall clock time when this action was triggered
 */
public record SelfEventReachedConsensusAction(@NonNull Instant wallClockTime) implements PlatformStatusAction {}
