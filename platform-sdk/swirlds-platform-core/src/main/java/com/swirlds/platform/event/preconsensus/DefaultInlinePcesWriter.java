// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.platform.event.preconsensus.PcesUtilities.getDatabaseDirectory;

import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

public class DefaultInlinePcesWriter implements InlinePcesWriter {
    private final PcesFileManager pcesFileManager;
    private final FileSyncOption fileSyncOption;
    private final NodeId selfId;
    /**
     * Constructor
     *
     * @param platformContext the platform context
     */
    public DefaultInlinePcesWriter(
            @NonNull final PlatformContext platformContext, final long initialRound, @NonNull final NodeId selfId) {
        Objects.requireNonNull(platformContext, "platformContext is required");
        final Path databaseDirectory = getDatabaseDirectory(platformContext, selfId);
        this.pcesFileManager = new PcesFileManager(platformContext, initialRound, databaseDirectory);

        this.selfId = selfId;
        final PcesConfig pcesConfig = platformContext.getConfiguration().getConfigData(PcesConfig.class);
        this.fileSyncOption = pcesConfig.inlinePcesSyncOption();
    }

    @Override
    public void beginStreamingNewEvents() {
        pcesFileManager.beginStreamingNewEvents();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public PlatformEvent writeEvent(@NonNull final PlatformEvent event) {
        // if we aren't streaming new events yet, assume that the given event is already durable
        if (!pcesFileManager.isStreamingNewEvents()) {
            return event;
        }

        if (event.getBirthRound() < pcesFileManager.getNonAncientBoundary()) {
            // don't do anything with ancient events
            return event;
        }

        try {
            pcesFileManager.prepareOutputStream(event);
            pcesFileManager.writeEvent(event);

            if (fileSyncOption == FileSyncOption.EVERY_EVENT
                    || (fileSyncOption == FileSyncOption.EVERY_SELF_EVENT
                            && event.getCreatorId().equals(selfId))) {

                pcesFileManager.sync();
            }
            return event;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerDiscontinuity(@NonNull Long newOriginRound) {
        pcesFileManager.registerDiscontinuity(newOriginRound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateNonAncientEventBoundary(@NonNull EventWindow nonAncientBoundary) {
        pcesFileManager.updateNonAncientEventBoundary(nonAncientBoundary);
    }

    @Override
    public void setMinimumAncientIdentifierToStore(@NonNull final Long minimumAncientIdentifierToStore) {
        pcesFileManager.setMinimumAncientIdentifierToStore(minimumAncientIdentifierToStore);
    }
}
