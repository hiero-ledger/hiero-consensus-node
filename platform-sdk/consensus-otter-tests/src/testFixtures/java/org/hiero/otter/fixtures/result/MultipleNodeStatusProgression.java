// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.Node;

/**
 * Interface that provides access to the status progression of a group of nodes.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
public interface MultipleNodeStatusProgression {

    /**
     * Returns the list of {@link SingleNodeStatusProgression} for all nodes
     *
     * @return the list of status progressions
     */
    @NonNull
    List<SingleNodeStatusProgression> statusProgressions();

    /**
     * Excludes the status progression of a specific node from the current results.
     *
     * @param node the node which status progression is to be excluded
     * @return a new instance of {@link MultipleNodeStatusProgression} with the specified node's status progression excluded
     */
    @NonNull
    MultipleNodeStatusProgression ignoring(@NonNull Node node);
}
