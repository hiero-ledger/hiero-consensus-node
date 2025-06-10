// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

public class DefaultInlinePcesWriter implements InlinePcesWriter {

    private final CommonPcesWriter commonPcesWriter;
    private final NodeId selfId;
    private final FileSyncOption fileSyncOption;
    private final PcesWriterPerEventMetrics pcesWriterPerEventMetrics;

    /**
     * Constructor
     *
     * @param platformContext the platform context
     * @param fileManager     manages all preconsensus event stream files currently on disk
     */
    public DefaultInlinePcesWriter(
            @NonNull final PlatformContext platformContext,
            @NonNull final PcesFileManager fileManager,
            @NonNull final NodeId selfId) {
        Objects.requireNonNull(platformContext, "platformContext is required");
        Objects.requireNonNull(fileManager, "fileManager is required");
        this.commonPcesWriter = new CommonPcesWriter(platformContext, fileManager);
        this.selfId = Objects.requireNonNull(selfId, "selfId is required");
        this.fileSyncOption = platformContext
                .getConfiguration()
                .getConfigData(PcesConfig.class)
                .inlinePcesSyncOption();

        this.pcesWriterPerEventMetrics =
                new PcesWriterPerEventMetrics(platformContext.getMetrics(), platformContext.getTime());
    }

    @Override
    public void beginStreamingNewEvents() {
        commonPcesWriter.beginStreamingNewEvents();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformEvent writeEvent(@NonNull final PlatformEvent event) {
        pcesWriterPerEventMetrics.startWriteEvent();

        // if we aren't streaming new events yet, assume that the given event is already durable
        if (!commonPcesWriter.isStreamingNewEvents()) {
            return event;
        }

        if (event.getBirthRound() < commonPcesWriter.getNonAncientBoundary()) {
            // don't do anything with ancient events
            return event;
        }

        try {
            commonPcesWriter.prepareOutputStream(event);
            pcesWriterPerEventMetrics.startFileWrite();
            final long size = commonPcesWriter.getCurrentMutableFile().writeEvent(event);
            pcesWriterPerEventMetrics.endFileWrite(size);

            if (fileSyncOption == FileSyncOption.EVERY_EVENT
                    || (fileSyncOption == FileSyncOption.EVERY_SELF_EVENT
                            && event.getCreatorId().equals(selfId))) {

                pcesWriterPerEventMetrics.startFileSync();
                commonPcesWriter.getCurrentMutableFile().sync();
                pcesWriterPerEventMetrics.endFileSync();
            }
            return event;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            pcesWriterPerEventMetrics.endWriteEvent();
            pcesWriterPerEventMetrics.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerDiscontinuity(@NonNull Long newOriginRound) {
        commonPcesWriter.registerDiscontinuity(newOriginRound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateNonAncientEventBoundary(@NonNull EventWindow nonAncientBoundary) {
        commonPcesWriter.updateNonAncientEventBoundary(nonAncientBoundary);
    }

    @Override
    public void setMinimumAncientIdentifierToStore(@NonNull final Long minimumAncientIdentifierToStore) {
        commonPcesWriter.setMinimumAncientIdentifierToStore(minimumAncientIdentifierToStore);
    }
}
