// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.hiero.otter.fixtures.logging.StructuredLog;

@FunctionalInterface
public interface MarkerFilter extends OtterFilter<StructuredLog> {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default MarkerFilter and(@NonNull final Predicate<? super StructuredLog> other) {
        return t -> test(t) && other.test(t);
    }

    /**
     * Combines all filters into a single filter that accepts a marker if all filters accept it.
     *
     * @param filters the filters to combine
     * @return a filter that accepts a marker if all filters accept it
     */
    static MarkerFilter andAll(@Nullable final MarkerFilter... filters) {
        if (filters == null || filters.length == 0) {
            return marker -> true; // No filters, accept all markers
        }
        return Arrays.stream(filters).reduce(x -> true, MarkerFilter::and);
    }

    /**
     * Creates a filter that excludes logs with the specified markers.
     *
     * @param first the first marker to exclude
     * @param rest additional markers to exclude
     * @return a filter that excludes logs with the specified markers
     */
    static MarkerFilter exclude(@NonNull LogMarker first, @Nullable final LogMarker... rest) {
        Objects.requireNonNull(first, "marker cannot be null");
        final List<MarkerFilter> combinedFilters = new ArrayList<>();
        combinedFilters.add(createExcludeFilterByMarker(first));
        if (rest != null && rest.length > 0) {
            Arrays.stream(rest).forEach(filter -> combinedFilters.add(createExcludeFilterByMarker(filter)));
        }

        return andAll(combinedFilters.toArray(new MarkerFilter[0]));
    }

    /**
     * Creates a filter that excludes logs with the specified marker.
     *
     * @param marker the marker to exclude
     * @return a filter that excludes logs with the specified marker
     */
    @NonNull
    private static MarkerFilter createExcludeFilterByMarker(@NonNull final LogMarker marker) {
        return structuredLog -> !Objects.equals(marker.getMarker(), structuredLog.marker());
    }

    /**
     * Creates a filter that includes logs with the specified markers.
     *
     * @param first the first marker to include
     * @param rest additional markers to include
     * @return a filter that includes logs with the specified markers
     */
    static MarkerFilter include(@NonNull LogMarker first, @Nullable final LogMarker... rest) {
        Objects.requireNonNull(first, "marker cannot be null");
        final List<MarkerFilter> combinedFilters = new ArrayList<>();
        combinedFilters.add(createIncludeFilterByMarker(first));
        if (rest != null && rest.length > 0) {
            Arrays.stream(rest).forEach(filter -> combinedFilters.add(createIncludeFilterByMarker(filter)));
        }

        return andAll(combinedFilters.toArray(new MarkerFilter[0]));
    }

    /**
     * Creates a filter that includes logs with the specified marker.
     *
     * @param marker the marker to include
     * @return a filter that includes logs with the specified marker
     */
    @NonNull
    private static MarkerFilter createIncludeFilterByMarker(@NonNull final LogMarker marker) {
        return structuredLog -> Objects.equals(marker.getMarker(), structuredLog.marker());
    }
}
