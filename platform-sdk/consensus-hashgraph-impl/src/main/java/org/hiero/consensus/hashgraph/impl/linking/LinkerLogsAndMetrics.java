// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.linking;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.hashgraph.impl.EventImpl;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Logs and metrics for the {@link ConsensusLinker}
 */
public interface LinkerLogsAndMetrics {
    /**
     * This method is called when a child event has a missing parent.
     *
     * @param child            the child event
     * @param parentDescriptor the descriptor of the missing parent
     */
    void childHasMissingParent(@NonNull PlatformEvent child, @NonNull EventDescriptorWrapper parentDescriptor);

    /**
     * This method is called when a child event has a parent with a different birth round than claimed.
     *
     * @param child            the child event
     * @param parentDescriptor the claimed descriptor of the parent
     * @param candidateParent  the parent event that we found in the parentHashMap
     */
    void parentHasIncorrectBirthRound(
            @NonNull PlatformEvent child,
            @NonNull EventDescriptorWrapper parentDescriptor,
            @NonNull EventImpl candidateParent);

    /**
     * This method is called when a child event has a self parent with a time created that is not strictly before the
     * child's time created.
     *
     * @param child             the child event
     * @param candidateParent   the parent event that we found in the parentHashMap
     * @param parentTimeCreated the time created of the parent event
     * @param childTimeCreated  the time created of the child event
     */
    void childTimeIsNotAfterSelfParentTime(
            @NonNull PlatformEvent child,
            @NonNull EventImpl candidateParent,
            @NonNull Instant parentTimeCreated,
            @NonNull Instant childTimeCreated);
}
