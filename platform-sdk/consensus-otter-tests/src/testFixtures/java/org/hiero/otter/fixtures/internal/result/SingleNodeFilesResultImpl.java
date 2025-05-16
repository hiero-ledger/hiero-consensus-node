// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SavedStateResult;
import org.hiero.otter.fixtures.result.SingleNodeFilesResult;

/**
 * The default implementation of {@link SingleNodeFilesResult}.
 */
public class SingleNodeFilesResultImpl extends PcesFilesResultImpl implements SingleNodeFilesResult {

    private final List<SavedStateResult> savedStates;

    /**
     * Constructor for {@code SingleNodeFilesResult}.
     *
     * @param nodeId The {@link NodeId} of the files' node
     * @param pcesFileTracker The {@link PcesFileTracker} that tracks the PcesFiles
     * @param ancientMode The {@link AncientMode}
     */
    public SingleNodeFilesResultImpl(
            @NonNull NodeId nodeId,
            @NonNull PcesFileTracker pcesFileTracker,
            @NonNull AncientMode ancientMode,
            @NonNull List<SavedStateResult> savedStates) {
        super(nodeId, pcesFileTracker, ancientMode);
        this.savedStates = List.copyOf(savedStates);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<SavedStateResult> savedStates() {
        return savedStates;
    }
}
