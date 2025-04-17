// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * A {@link LogFilter} that filters log messages based on their {@code selfId}.
 *
 * <p>This filter allows inclusion or exclusion of log messages depending on whether
 * their {@code nodeId} is present in a given set of node IDs.
 *
 * @param include if {@code true}, messages whose node ID is <b>not</b> in the set are filtered out;
 *                if {@code false}, messages whose node ID <b>is</b> in the set are filtered out
 * @param nodeIds the set of node IDs used for filtering
 */
public record LogNodeFilter(boolean include, @NonNull Set<Long> nodeIds) implements LogFilter {

    /**
     * Determines whether the given log message should be filtered based on its node ID.
     *
     * <ul>
     *   <li>If {@code include} is {@code true}, this returns {@code true} (filter out)
     *       when the message's node ID is <b>not</b> in the {@code nodeIds} set.</li>
     *   <li>If {@code include} is {@code false}, this returns {@code true} when the message's
     *       node ID <b>is</b> in the {@code nodeIds} set.</li>
     * </ul>
     *
     * @param logMsg the structured log message to evaluate
     *
     * @return {@code true} if the message should be filtered out, {@code false} otherwise
     */
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        return include != nodeIds.contains(logMsg.nodeId());
    }
}
