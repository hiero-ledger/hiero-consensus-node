// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * A filter to select only specific nodes from a list of nodes.
 */
@FunctionalInterface
public interface NodeFilter extends Predicate<Node> {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    default NodeFilter and(@NonNull final Predicate<? super Node> other) {
        return t -> test(t) && other.test(t);
    }

    /**
     * Combines all filters into a single filter that accepts a node if all filters accept it.
     *
     * @param filters the filters to combine
     * @return a filter that accepts a node if all filters accept it
     */
    static NodeFilter andAll(NodeFilter... filters) {
        if (filters == null || filters.length == 0) {
            return node -> true; // No filters, accept all nodes
        }
        return Arrays.stream(filters).reduce(x -> true, NodeFilter::and);
    }
}
