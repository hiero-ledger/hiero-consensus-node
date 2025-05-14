// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the log results of a group of nodes that were created during a test.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
public interface MultipleNodeLogResults {

    /**
     * Returns the list of {@link SingleNodeLogResult} for all nodes
     *
     * @return the list of results
     */
    @NonNull
    List<SingleNodeLogResult> results();

    /**
     * Excludes the log results of a specific node from the current results.
     *
     * @param node the node whose log results are to be excluded
     * @return a new {@code MultipleNodeLogResults} instance with the specified node's results removed
     */
    @NonNull
    MultipleNodeLogResults ignoring(@NonNull Node node);

    /**
     * Excludes the log results associated with the specified log marker from the current results.
     *
     * @param marker the {@link LogMarker} whose associated log results are to be excluded
     * @return a new {@code MultipleNodeLogResults} instance with the specified log marker's results removed
     */
    @NonNull
    MultipleNodeLogResults ignoring(@NonNull LogMarker marker);
}
