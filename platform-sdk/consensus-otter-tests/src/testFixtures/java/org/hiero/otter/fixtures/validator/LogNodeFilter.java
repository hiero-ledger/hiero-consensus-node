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
 * @param mode    the filtering mode: {@code INCLUDE} means only messages with the given markers are allowed;
 *                {@code EXCLUDE} means messages with the given markers are filtered out.
 * @param nodeIds the set of node IDs used for filtering
 */
public record LogNodeFilter(@NonNull Mode mode, @NonNull Set<Long> nodeIds) implements LogFilter {

    /**
     * Determines whether the given log message should be filtered based on its node ID.
     *
     * <ul>
     *   <li>If {@code mode} is {@code INCLUDE}, this returns {@code true} (filter out)
     *       when the message's node ID is <b>not</b> in the {@code nodeIds} set.</li>
     *   <li>If {@code mode} is {@code EXCLUDE}, this returns {@code true} when the message's
     *       node ID <b>is</b> in the {@code nodeIds} set.</li>
     * </ul>
     *
     * @param logMsg the structured log message to evaluate
     *
     * @return {@code true} if the message should be filtered out, {@code false} otherwise
     */
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        final boolean contains = nodeIds.contains(logMsg.nodeId());
        return switch (mode) {
            case INCLUDE -> !contains;
            case EXCLUDE -> contains;
        };
    }
}
