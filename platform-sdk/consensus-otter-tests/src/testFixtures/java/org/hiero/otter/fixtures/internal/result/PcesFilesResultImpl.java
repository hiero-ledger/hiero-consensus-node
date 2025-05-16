// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.result.PcesFilesResult;

public abstract class PcesFilesResultImpl implements PcesFilesResult {

    private final NodeId nodeId;
    private final PcesFileTracker pcesFileTracker;
    private final AncientMode ancientMode;

    /**
     * Constructor for {@code PcesFilesResultImpl}.
     *
     * @param nodeId The {@link NodeId} of the files' node
     * @param pcesFileTracker The {@link PcesFileTracker} that tracks the PcesFiles
     * @param ancientMode The {@link AncientMode}
     */
    protected PcesFilesResultImpl(
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

    /**
     * Creates a {@link PcesFileTracker} from the provided information.
     *
     * @param platformContext The {@link PlatformContext}
     * @param databaseDirectory The directory where the PCES files are stored
     * @return a new {@link PcesFileTracker}
     * @throws java.io.UncheckedIOException if an I/O error occurs
     */
    public static PcesFileTracker createPcesFileTracker(
            @NonNull final PlatformContext platformContext, @NonNull final Path databaseDirectory) {
        try {
            final Configuration configuration = platformContext.getConfiguration();
            final PcesConfig pcesConfig = configuration.getConfigData(PcesConfig.class);
            final EventConfig eventConfig = configuration.getConfigData(EventConfig.class);

            return PcesFileReader.readFilesFromDisk(
                    platformContext, databaseDirectory, 0L, pcesConfig.permitGaps(), eventConfig.getAncientMode());
        } catch (IOException e) {
            throw new UncheckedIOException("Error initializing PcesFileTracker", e);
        }
    }
}
