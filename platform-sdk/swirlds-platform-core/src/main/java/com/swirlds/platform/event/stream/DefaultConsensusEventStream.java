// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.stream;

import static com.swirlds.base.units.UnitConstants.SECONDS_TO_MILLISECONDS;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.logging.legacy.LogMarker.EVENT_STREAM;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.INFO_CATEGORY;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.FunctionGauge;
import com.swirlds.common.stream.EventStreamType;
import com.swirlds.common.stream.HashCalculatorForStream;
import com.swirlds.common.stream.MultiStream;
import com.swirlds.common.stream.QueueThreadObjectStream;
import com.swirlds.common.stream.QueueThreadObjectStreamConfiguration;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.common.stream.RunningHashCalculatorForStream;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.stream.internal.TimestampStreamFileWriter;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.node.NodeId;

/**
 * This class is used for generating event stream files when enableEventStreaming is true, and for calculating
 * runningHash for consensus Events.
 */
public class DefaultConsensusEventStream implements ConsensusEventStream {
    private static final Logger logger = LogManager.getLogger(DefaultConsensusEventStream.class);

    /**
     * receives consensus events then passes to hashQueueThread and
     * writeQueueThread
     */
    private final MultiStream<CesEvent> multiStream;
    /**
     * check whether this event is the last event before restart
     */
    private final Predicate<CesEvent> isLastEventInFreezeCheck;
    /**
     * receives consensus events from multiStream, then passes to hashCalculator
     */
    private QueueThreadObjectStream<CesEvent> hashQueueThread;
    /**
     * receives consensus events from multiStream, then passes to streamFileWriter
     */
    private QueueThreadObjectStream<CesEvent> writeQueueThread;
    /**
     * receives consensus events from writeQueueThread, serializes consensus events to event stream files
     */
    private TimestampStreamFileWriter<CesEvent> streamFileWriter;
    /**
     * initialHash loaded from signed state
     */
    private Hash initialHash = new Hash(new byte[DigestType.SHA_384.digestLength()]);
    /**
     * When we freeze the platform, the last event to be written to EventStream file is the last event in the freeze
     * round. The freeze round is defined as the first round with a consensus timestamp after the start of the freeze
     * period.
     */
    private volatile boolean freezePeriodStarted = false;

    private final RateLimitedLogger eventAfterFreezeLogger;

    /**
     * @param platformContext          the platform context
     * @param selfId                   the id of this node
     * @param signer                   an object that can sign things
     * @param nodeName                 name of this node
     * @param isLastEventInFreezeCheck a predicate which checks whether this event is the last event before restart
     */
    public DefaultConsensusEventStream(
            @NonNull final PlatformContext platformContext,
            @NonNull final NodeId selfId,
            @NonNull final Signer signer,
            @NonNull final String nodeName,
            @NonNull final Predicate<CesEvent> isLastEventInFreezeCheck) {
        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(selfId);
        Objects.requireNonNull(signer);
        Objects.requireNonNull(nodeName);
        Objects.requireNonNull(isLastEventInFreezeCheck);

        eventAfterFreezeLogger = new RateLimitedLogger(logger, platformContext.getTime(), Duration.ofMinutes(1));

        final EventConfig eventConfig = platformContext.getConfiguration().getConfigData(EventConfig.class);
        final boolean enableEventStreaming = eventConfig.enableEventStreaming();
        final String eventsLogDir = eventConfig.eventsLogDir();
        final long eventsLogPeriod = eventConfig.eventsLogPeriod();
        final int eventStreamQueueCapacity = eventConfig.eventStreamQueueCapacity();

        if (enableEventStreaming) {
            // the directory to which event stream files are written
            final String eventStreamDir = eventsLogDir + "/events_" + nodeName;
            try {
                Files.createDirectories(Paths.get(eventStreamDir));
            } catch (final IOException e) {
                throw new IllegalStateException("Can not create directory for event stream", e);
            }

            streamFileWriter = new TimestampStreamFileWriter<>(
                    eventStreamDir,
                    eventsLogPeriod * SECONDS_TO_MILLISECONDS,
                    signer,
                    // when event streaming is started after reconnect, or at state recovering,
                    // startWriteAtCompleteWindow should be set to be true; when event streaming is started after
                    // restart, it should be set to be false
                    false,
                    EventStreamType.getInstance());

            writeQueueThread = new QueueThreadObjectStreamConfiguration<CesEvent>(getStaticThreadManager())
                    .setNodeId(selfId)
                    .setComponent("event-stream")
                    .setThreadName("write-queue")
                    .setCapacity(eventStreamQueueCapacity)
                    .setForwardTo(streamFileWriter)
                    .build();
            writeQueueThread.start();
        }

        platformContext
                .getMetrics()
                .getOrCreate(new FunctionGauge.Config<>(
                                INFO_CATEGORY, "eventStreamQueueSize", Integer.class, this::getEventStreamingQueueSize)
                        .withDescription("size of the queue from which we take events and write to EventStream file")
                        .withUnit("count"));

        platformContext
                .getMetrics()
                .getOrCreate(new FunctionGauge.Config<>(
                                INFO_CATEGORY, "hashQueueSize", Integer.class, this::getHashQueueSize)
                        .withDescription("size of the queue from which we take events, calculate Hash and RunningHash")
                        .withUnit("count"));

        // receives consensus events from hashCalculator, calculates and set runningHash for this event
        final RunningHashCalculatorForStream<CesEvent> runningHashCalculator = new RunningHashCalculatorForStream<>();

        // receives consensus events from hashQueueThread, calculates this event's Hash, then passes to
        // runningHashCalculator
        final HashCalculatorForStream<CesEvent> hashCalculator = new HashCalculatorForStream<>(runningHashCalculator);
        hashQueueThread = new QueueThreadObjectStreamConfiguration<CesEvent>(getStaticThreadManager())
                .setNodeId(selfId)
                .setComponent("event-stream")
                .setThreadName("hash-queue")
                .setCapacity(eventStreamQueueCapacity)
                .setForwardTo(hashCalculator)
                .build();
        hashQueueThread.start();

        multiStream = new MultiStream<>(
                enableEventStreaming ? List.of(hashQueueThread, writeQueueThread) : List.of(hashQueueThread));
        multiStream.setRunningHash(initialHash);

        this.isLastEventInFreezeCheck = isLastEventInFreezeCheck;
    }

    /**
     * @param time                     provides wall clock time
     * @param multiStream              the instance which receives consensus events from the transaction handler, then
     *                                 passes to nextStreams
     * @param isLastEventInFreezeCheck a predicate which checks whether this event is the last event before restart
     */
    public DefaultConsensusEventStream(
            @NonNull final Time time,
            @NonNull final MultiStream<CesEvent> multiStream,
            @NonNull final Predicate<CesEvent> isLastEventInFreezeCheck) {
        eventAfterFreezeLogger = new RateLimitedLogger(logger, Objects.requireNonNull(time), Duration.ofMinutes(1));
        this.multiStream = Objects.requireNonNull(multiStream);
        multiStream.setRunningHash(initialHash);
        this.isLastEventInFreezeCheck = Objects.requireNonNull(isLastEventInFreezeCheck);
    }

    /**
     * Closes the multistream.
     * <p>
     * IMPORTANT: For unit test purposes only.
     */
    public void stop() {
        writeQueueThread.stop();
        hashQueueThread.stop();

        streamFileWriter.close();
        multiStream.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addEvents(@NonNull final List<CesEvent> events) {
        events.forEach(event -> {
            if (!freezePeriodStarted) {
                multiStream.addObject(event);
                if (isLastEventInFreezeCheck.test(event)) {
                    freezePeriodStarted = true;
                    logger.info(
                            EVENT_STREAM.getMarker(),
                            "ConsensusTimestamp of the last Event to be written into file before restarting: {}",
                            event::getTimestamp);
                    multiStream.close();
                }
            } else {
                eventAfterFreezeLogger.warn(
                        EVENT_STREAM.getMarker(), "Event {} dropped after freezePeriodStarted!", event.getTimestamp());
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void legacyHashOverride(@NonNull final RunningEventHashOverride runningEventHashOverride) {
        try {
            if (hashQueueThread != null) {
                hashQueueThread.pause();
            }
            if (writeQueueThread != null) {
                writeQueueThread.pause();
            }
        } catch (final InterruptedException e) {
            logger.error(EXCEPTION.getMarker(), "Failed to pause queue threads", e);
            Thread.currentThread().interrupt();
        }

        if (streamFileWriter != null) {
            streamFileWriter.setStartWriteAtCompleteWindow(runningEventHashOverride.isReconnect());
        }

        initialHash = new Hash(runningEventHashOverride.legacyRunningEventHash());
        logger.info(EVENT_STREAM.getMarker(), "EventStreamManager::updateRunningHash: {}", initialHash);
        multiStream.setRunningHash(initialHash);

        if (hashQueueThread != null) {
            hashQueueThread.resume();
        }
        if (writeQueueThread != null) {
            writeQueueThread.resume();
        }
    }

    /**
     * returns current size of working queue for calculating hash and runningHash
     *
     * @return current size of working queue for calculating hash and runningHash
     */
    private int getHashQueueSize() {
        return hashQueueThread == null ? 0 : hashQueueThread.getQueue().size();
    }

    /**
     * returns current size of working queue for writing to event stream files
     *
     * @return current size of working queue for writing to event stream files
     */
    private int getEventStreamingQueueSize() {
        return writeQueueThread == null ? 0 : writeQueueThread.getQueue().size();
    }
}
