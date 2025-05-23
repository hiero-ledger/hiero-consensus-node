// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.Node;

public interface MultipleNodeMetricsResults {

    /**
     * Returns the list of {@link SingleNodeMetricsResult} for all nodes
     *
     * @return the list of results
     */
    @NonNull
    List<SingleNodeMetricsResult> results();

    /**
     * Excludes the log results of a specific node from the current results.
     *
     * @param node the node whose log results are to be excluded
     * @return a new {@code MultipleNodeLogResults} instance with the specified node's results removed
     */
    @NonNull
    MultipleNodeMetricsResults ignoring(@NonNull Node node);
}
