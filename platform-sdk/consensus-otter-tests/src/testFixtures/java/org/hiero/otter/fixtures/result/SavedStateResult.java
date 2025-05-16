// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * A {@link SavedStateResult} provides access to all relevant files stored in a saved state.
 */
public interface SavedStateResult extends PcesFilesResult {

    /**
     * Returns the node ID of the files' node
     *
     * @return the node ID
     */
    @Override
    @NonNull
    NodeId nodeId();

    /**
     * Returns the {@link SavedStateMetadata} of the saved state.
     *
     * @return the {@link SavedStateMetadata}
     */
    @NonNull
    SavedStateMetadata metadata();
}
