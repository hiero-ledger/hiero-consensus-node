// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.Marker;
import org.hiero.otter.fixtures.Validator.LogFilter;
import org.hiero.otter.fixtures.logging.StructuredLog;

public record LogMarkerFilter(boolean include, @NonNull Set<Marker> markers) implements LogFilter {

    @Override
    public boolean filter(@NonNull final StructuredLog logMsg) {
        return include != markers.contains(logMsg.marker());
    }
}
