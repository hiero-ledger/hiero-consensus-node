// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.linking;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

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
    public void childTimeIsNotAfterSelfParentTime(
            @NonNull final PlatformEvent child,
            @NonNull final EventImpl candidateParent,
            @NonNull final Instant parentTimeCreated,
            @NonNull final Instant childTimeCreated) {}

    @Override
    public void eventLinked() {}

    @Override
    public void eventUnlinked() {}
}
