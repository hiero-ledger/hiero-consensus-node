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
 * @param include if {@code true}, messages whose marker is <b>not</b> in the set are filtered out;
 *                if {@code false}, messages whose marker <b>is</b> in the set are filtered out
 * @param markers the set of markers used for filtering
 */
public record LogMarkerFilter(boolean include, @NonNull Set<Marker> markers) implements LogFilter {

    /**
     * Determines whether the given log message should be filtered based on its marker.
     *
     * <ul>
     *   <li>If {@code include} is {@code true}, this returns {@code true} (filter out)
     *       when the message's marker is <b>not</b> in the {@code markers} set.</li>
     *   <li>If {@code include} is {@code false}, this returns {@code true} when the message's
     *       marker <b>is</b> in the {@code markers} set.</li>
     * </ul>
     *
     * @param logMsg the structured log message to evaluate
     *
     * @return {@code true} if the message should be filtered out, {@code false} otherwise
     */
    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        return include != markers.contains(logMsg.marker());
    }
}
