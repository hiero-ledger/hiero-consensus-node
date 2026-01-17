// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.actions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * An action that indicates an amount of wall clock time has elapsed.
 * <p>
 * Triggered periodically when other actions aren't being processed.
 *
 * @param instant          the instant when this action was triggered
 * @param quiescingStatus  the current quiescing status
 */
public record TimeElapsedAction(@NonNull Instant instant, @NonNull QuiescingStatus quiescingStatus)
        implements PlatformStatusAction {

    /**
     * Encapsulates the quiescing state information.
     *
     * @param isQuiescing whether the platform is currently quiescing
     * @param since       the instant when the current quiescing state began
     */
    public record QuiescingStatus(boolean isQuiescing, @NonNull Instant since) {}
}
