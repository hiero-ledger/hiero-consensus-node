// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.writer;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.config.FileSyncOption;
import org.hiero.consensus.pces.config.PcesConfig;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;

public class DefaultInlinePcesWriter implements InlinePcesWriter {

    private final CommonPcesWriter commonPcesWriter;
    private final NodeId selfId;
    private final FileSyncOption fileSyncOption;
    private final PcesWriterPerEventMetrics pcesWriterPerEventMetrics;

    /**
     * Are we in the middle of component shutdown? If yes, ignore incoming events
     */
    private volatile boolean beingDestroyed;

    /**
     * Set to true while we are in the middle of processing events, to synchronize with destruction logic
     */
    private volatile boolean processingEvent;

    /**
     * Syncs and closes the current file if the JVM exits without {@link #destroy()} having been called. Held so that it
     * can be deregistered once {@link #destroy()} has run, which keeps a hook per writer instance from accumulating for
     * the lifetime of the JVM.
     */
    private final Thread shutdownHook;

    /**
     * Constructor
     *
     * @param configuration    the configuration of the platform
     * @param metrics          the metrics system of the platform
     * @param time             the time source of the platform
     * @param commonPcesWriter the common writer that manages file I/O
     * @param selfId           the ID of this node
     */
    public DefaultInlinePcesWriter(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final CommonPcesWriter commonPcesWriter,
            @NonNull final NodeId selfId) {
        this.commonPcesWriter = requireNonNull(commonPcesWriter, "commonPcesWriter is required");
        this.selfId = requireNonNull(selfId, "selfId is required");
        this.fileSyncOption = configuration.getConfigData(PcesConfig.class).inlinePcesSyncOption();

        this.pcesWriterPerEventMetrics = new PcesWriterPerEventMetrics(metrics, time);

        this.shutdownHook = new Thread(this::destroy, "pces-shutdown-sync");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
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

        // if we aren't streaming new events yet, assume that the given event is already durable
        if (!commonPcesWriter.isStreamingNewEvents()) {
            return event;
        }

        if (event.getBirthRound() < commonPcesWriter.getNonAncientBoundary()) {
            // don't do anything with ancient events
            return event;
        }

        // we need to check first time, as we don't want end up missing processingEvent==false gap
        // in destroy() method
        if (beingDestroyed) {
            return event;
        }

        try {
            pcesWriterPerEventMetrics.startWriteEvent();
            processingEvent = true;
            if (beingDestroyed) {
                // we need to check second time, it might have changed in between
                return event;
            }

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
            processingEvent = false;
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
    public void setMinimumBirthRoundToStore(@NonNull final Long minimumBirthRoundToStore) {
        commonPcesWriter.setMinimumBirthRoundToStore(minimumBirthRoundToStore);
    }

    /**
     * Cleanup/destroy method which makes sure we are not in the middle of processing the event
     * when we close PCES file; this instance of PcesWriter is not usable and not possible to recover after using it.
     * This method will be called from a random thread, take care about memory visibility versus rest of the class
     */
    @Override
    public void destroy() {
        this.beingDestroyed = true;
        while (this.processingEvent) {
            Thread.yield();
        }
        this.commonPcesWriter.destroy();
        deregisterShutdownHook();
    }

    /**
     * Deregister the shutdown hook now that the file has been synced and closed, so that a writer belonging to a
     * stopped node is not retained until the JVM exits. Called on every {@link #destroy()}, including the one the hook
     * itself performs.
     */
    private void deregisterShutdownHook() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (final IllegalStateException e) {
            // The JVM is already shutting down, which is the case when the hook itself called destroy().
            // There is nothing to deregister.
        }
    }
}
