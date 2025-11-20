// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.linking;

import com.swirlds.platform.internal.EventImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.roster.RosterHistory;

/**
 * A no-operation implementation of {@link LinkerLogsAndMetrics} that does nothing.
 */
public class NoOpLinkerLogsAndMetrics implements LinkerLogsAndMetrics {
    /** The singleton instance */
    private static final LinkerLogsAndMetrics SINGLETON = new NoOpLinkerLogsAndMetrics();

    /**
     * Get an instance of the no-op linker logs and metrics.
     *
     * @return an instance
     */
    public static LinkerLogsAndMetrics getInstance() {
        return SINGLETON;
    }

    private NoOpLinkerLogsAndMetrics() {
        // private constructor to prevent instantiation
    }

    @Override
    public void childHasMissingParent(
            @NonNull final PlatformEvent child, @NonNull final EventDescriptorWrapper parentDescriptor) {}

    @Override
    public void parentHasIncorrectBirthRound(
            @NonNull final PlatformEvent child,
            @NonNull final EventDescriptorWrapper parentDescriptor,
            @NonNull final EventImpl candidateParent) {}

    @Override
    public void missingRosterForEvent(@NonNull final PlatformEvent event, @NonNull final RosterHistory rosterHistory) {}

    @Override
    public void childTimeIsNotAfterSelfParentTime(
            @NonNull final PlatformEvent child,
            @NonNull final EventImpl candidateParent,
            @NonNull final Instant parentTimeCreated,
            @NonNull final Instant childTimeCreated) {}
}
