// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.helpers;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.Marker;

/**
 * Utility class for the Otter framework.
 */
public class Utils {

    private Utils() {}

    /**
     * Gathers markers in a {@link Set}
     *
     * @param first the first log marker
     * @param rest additional optional log markers
     * @return a set of markers collected from the provided log markers
     */
    public static Set<Marker> collectMarkers(@NonNull final LogMarker first, @Nullable final LogMarker... rest) {
        final Stream<LogMarker> restStream = rest == null ? Stream.empty() : Arrays.stream(rest);
        return Stream.concat(Stream.of(first), restStream)
                .map(LogMarker::getMarker)
                .collect(Collectors.toSet());
    }
}
