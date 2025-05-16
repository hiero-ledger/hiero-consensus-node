// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SingleNodeFilesResult;

/**
 * The default implementation of {@link SingleNodeFilesResult}.
 */
public class SingleNodeFilesResultImpl implements SingleNodeFilesResult {

    private final NodeId nodeId;
    private final PcesFileTracker pcesFileTracker;
    private final AncientMode ancientMode;

    /**
     * Constructor for SingleNodeFilesResultImpl.
     *
     * @param nodeId The {@link NodeId} of the files' node
     * @param pcesFileTracker The {@link PcesFileTracker} that tracks the PcesFiles
     * @param ancientMode The {@link AncientMode}
     */
    public SingleNodeFilesResultImpl(
            @NonNull final NodeId nodeId,
            @NonNull final PcesFileTracker pcesFileTracker,
            @NonNull final AncientMode ancientMode) {
        this.nodeId = requireNonNull(nodeId);
        this.pcesFileTracker = requireNonNull(pcesFileTracker);
        this.ancientMode = requireNonNull(ancientMode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeId nodeId() {
        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Iterator<PcesFile> pcesFiles() {
        return pcesFileTracker.getFileIterator();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public PcesMultiFileIterator pcesEvents() {
        return new PcesMultiFileIterator(PcesFileManager.NO_LOWER_BOUND, pcesFiles(), ancientMode);
    }
}
