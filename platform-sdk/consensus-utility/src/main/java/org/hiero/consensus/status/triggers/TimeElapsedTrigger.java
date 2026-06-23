// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * A trigger that indicates an amount of wall clock time has elapsed.
 * <p>
 * Triggered periodically when other triggers aren't being processed.
 *
 * @param instant          the instant when this trigger fired
 * @param quiescingStatus  the current quiescing status
 */
public record TimeElapsedTrigger(
        @NonNull Instant instant, @NonNull QuiescingStatus quiescingStatus) implements StatusMachineTrigger {

    /**
     * Encapsulates the quiescing state information.
     *
     * @param isQuiescing whether the platform is currently quiescing
     * @param since       the instant when the current quiescing state began
     */
    public record QuiescingStatus(
            boolean isQuiescing, @NonNull Instant since) {}
}
