// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

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
}
