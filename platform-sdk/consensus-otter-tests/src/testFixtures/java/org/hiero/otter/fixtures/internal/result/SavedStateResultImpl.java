// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.SavedStateResult;

public class SavedStateResultImpl extends PcesFilesResultImpl implements SavedStateResult {

    private final SavedStateInfo savedStateInfo;

    /**
     * Constructor for {@code SavedStateResultImpl}.
     *
     * @param nodeId The {@link NodeId} of the files' node
     * @param pcesFileTracker The {@link PcesFileTracker} that tracks the PcesFiles
     * @param ancientMode The {@link AncientMode}
     */
    public SavedStateResultImpl(
            @NonNull final NodeId nodeId,
            @NonNull final PcesFileTracker pcesFileTracker,
            @NonNull final AncientMode ancientMode,
            @NonNull final SavedStateInfo savedStateInfo) {
        super(nodeId, pcesFileTracker, ancientMode);
        this.savedStateInfo = savedStateInfo;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SavedStateMetadata metadata() {
        return savedStateInfo.metadata();
    }

    /**
     * Creates a {@link SavedStateResult} from the provided information.
     *
     * @param platformContext the platform context
     * @param savedStateInfo the saved state info
     * @return a new {@link SavedStateResult}
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static SavedStateResult createSavedStateResult(
            @NonNull final PlatformContext platformContext, @NonNull final SavedStateInfo savedStateInfo) {
        final NodeId nodeId = savedStateInfo.metadata().nodeId();
        final AncientMode ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
        final Path path = savedStateInfo.stateFile().getParent();
        final PcesFileTracker pcesFileTracker = PcesFilesResultImpl.createPcesFileTracker(platformContext, path);
        return new SavedStateResultImpl(nodeId, pcesFileTracker, ancientMode, savedStateInfo);
    }
}
