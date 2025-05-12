// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * A filter to select only specific nodes from a list of nodes.
 */
@FunctionalInterface
public interface NodeFilter extends OtterFilter<Node> {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default NodeFilter and(@NonNull final Predicate<? super Node> other) {
        return t -> test(t) && other.test(t);
    }

    /**
     * Creates a filter that accepts only the specified nodes.
     *
     * @param first the first node to include
     * @param rest additional nodes to include
     * @return a filter that accepts only the specified nodes
     */
    static NodeFilter only(@NonNull final Node first, @Nullable final Node... rest) {
        Objects.requireNonNull(first, "node cannot be null");
        final List<NodeFilter> combinedFilters = new ArrayList<>();
        combinedFilters.add(createOnlyNodeFilter(first));
        if (rest != null && rest.length > 0) {
            Arrays.stream(rest).forEach(filter -> combinedFilters.add(createOnlyNodeFilter(filter)));
        }

        return andAll(combinedFilters.toArray(new NodeFilter[0]));
    }

    @NonNull
    private static NodeFilter createOnlyNodeFilter(@NonNull final Node node) {
        return n -> n.getSelfId().equals(node.getSelfId());
    }

    /**
     * Creates a filter that excludes the specified nodes.
     *
     * @param first the first node to exclude
     * @param rest additional nodes to exclude
     * @return a filter that excludes the specified nodes
     */
    static NodeFilter without(@NonNull final Node first, @Nullable final Node... rest) {
        Objects.requireNonNull(first, "node cannot be null");
        final List<NodeFilter> combinedFilters = new ArrayList<>();
        combinedFilters.add(createWithoutNodeFilter(first));
        if (rest != null && rest.length > 0) {
            Arrays.stream(rest).forEach(filter -> combinedFilters.add(createWithoutNodeFilter(filter)));
        }

        return andAll(combinedFilters.toArray(new NodeFilter[0]));
    }

    @NonNull
    private static NodeFilter createWithoutNodeFilter(@NonNull final Node node) {
        return n -> !n.getSelfId().equals(node.getSelfId());
    }

    /**
     * Combines all filters into a single filter that accepts a node if all filters accept it.
     *
     * @param filters the filters to combine
     * @return a filter that accepts a node if all filters accept it
     */
    static NodeFilter andAll(@Nullable final NodeFilter... filters) {
        if (filters == null || filters.length == 0) {
            return node -> true; // No filters, accept all nodes
        }
        return Arrays.stream(filters).reduce(x -> true, NodeFilter::and);
    }
}
