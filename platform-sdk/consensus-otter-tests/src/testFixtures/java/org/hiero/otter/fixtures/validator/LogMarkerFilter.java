// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.Marker;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * A {@link LogFilter} that filters log messages based on their {@link Marker}.
 *
 * <p>This filter allows inclusion or exclusion of log messages depending on whether
 * their marker is present in a given set of markers.
 *
 * @param mode    the filtering mode: {@code INCLUDE} means only messages with the given markers are allowed;
 *                {@code EXCLUDE} means messages with the given markers are filtered out.
 * @param markers the set of markers used for filtering
 */
public record LogMarkerFilter(@NonNull Mode mode, @NonNull Set<Marker> markers) implements LogFilter {

    /**
     * Determines whether the given log message should be filtered based on its marker.
     *
     * <ul>
     *   <li>If {@code mode} is {@code INCLUDE}, this returns {@code true} (filter out)
     *       when the message's marker is <b>not</b> in the {@code markers} set.</li>
     *   <li>If {@code mode} is {@code EXCLUDE}, this returns {@code true} when the message's
     *       marker <b>is</b> in the {@code markers} set.</li>
     * </ul>
     *
     * @param logMsg the structured log message to evaluate
     *
     * @return {@code true} if the message should be filtered out, {@code false} otherwise
     */
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        final boolean contains = markers.contains(logMsg.marker());
        return switch (mode) {
            case INCLUDE -> !contains;
            case EXCLUDE -> contains;
        };
    }
}
