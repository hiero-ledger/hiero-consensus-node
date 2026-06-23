// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A trigger fired when the platform observes a self event reaching consensus.
 *
 * @param wallClockTime the wall clock time when this trigger fired
 */
public record SelfEventReachedConsensusTrigger(@NonNull Instant wallClockTime) implements StatusMachineTrigger {}
