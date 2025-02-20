// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.event.preconsensus;

import static com.swirlds.platform.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static com.swirlds.platform.event.AncientMode.GENERATION_THRESHOLD;
import static com.swirlds.platform.event.preconsensus.PcesFileManager.NO_LOWER_BOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.io.FileManipulation;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.config.TransactionConfig_;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.AncientMode;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.event.preconsensus.DefaultPcesSequencer;
import com.swirlds.platform.event.preconsensus.DefaultPcesWriter;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesSequencer;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.event.preconsensus.PcesWriter;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.test.fixtures.event.PcesWriterTestUtils;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("PcesWriter Tests")
class PcesWriterTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    private final NodeId selfId = NodeId.of(0);
    private final int numEvents = 1_000;

    protected static Stream<Arguments> buildArguments() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        Files.createDirectories(testDirectory);
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
    }

    @NonNull
    private PlatformContext buildContext(@NonNull final AncientMode ancientMode) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, testDirectory)
                .withValue(FileSystemManagerConfig_.ROOT_PATH, testDirectory)
                .withValue(PcesConfig_.PREFERRED_FILE_SIZE_MEGABYTES, 5)
                .withValue(TransactionConfig_.MAX_TRANSACTION_BYTES_PER_EVENT, Integer.MAX_VALUE)
                .withValue(TransactionConfig_.MAX_TRANSACTION_COUNT_PER_EVENT, Integer.MAX_VALUE)
                .withValue(TransactionConfig_.TRANSACTION_MAX_BYTES, Integer.MAX_VALUE)
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();

        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(new FakeTime(Duration.ofMillis(1)))
                .build();
    }

    /**
     * Pass the most recent durable sequence number to the output wire.
     * <p>
     * The intention of this method is to simply pass the return value from any writer call that returns a sequence
     * number. This simulates the components being wired together.
     *
     * @param mostRecentDurableSequenceNumber the most recent durable sequence number
     * @param latestDurableSequenceNumber     container for the latest durable sequence number
     */
    private static void passValueToDurabilityNexus(
            @Nullable final Long mostRecentDurableSequenceNumber,
            @NonNull final AtomicLong latestDurableSequenceNumber) {
        if (mostRecentDurableSequenceNumber != null) {
            latestDurableSequenceNumber.set(mostRecentDurableSequenceNumber);
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Standard Operation Test")
    void standardOperationTest(@NonNull final AncientMode ancientMode) throws IOException {

        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformContext platformContext = buildContext(ancientMode);

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);
        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesSequencer sequencer = new DefaultPcesSequencer();
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultPcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);
        final AtomicLong latestDurableSequenceNumber = new AtomicLong();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        writer.beginStreamingNewEvents();

        final Collection<PlatformEvent> rejectedEvents = new HashSet<>();

        long lowerBound = ancientMode.selectIndicator(0, 1);
        final Iterator<PlatformEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            final PlatformEvent event = iterator.next();

            sequencer.assignStreamSequenceNumber(event);
            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);

            lowerBound = Math.max(lowerBound, event.getAncientIndicator(ancientMode) - stepsUntilAncient);

            writer.updateNonAncientEventBoundary(new EventWindow(1, lowerBound, lowerBound, ancientMode));

            if (event.getAncientIndicator(ancientMode) < lowerBound) {
                // Although it's not common, it's possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                rejectedEvents.add(event);
                iterator.remove();
            }

            // request a flush sometimes
            if (random.nextInt(10) == 0) {
                passValueToDurabilityNexus(
                        writer.submitFlushRequest(event.getStreamSequenceNumber()), latestDurableSequenceNumber);
            }
        }

        passValueToDurabilityNexus(
                writer.submitFlushRequest(events.getLast().getStreamSequenceNumber()), latestDurableSequenceNumber);

        events.forEach(event -> assertTrue(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));
        rejectedEvents.forEach(
                event -> assertFalse(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));

        PcesWriterTestUtils.verifyStream(selfId, events, platformContext, 0, ancientMode);

        writer.closeCurrentMutableFile();
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Ancient Event Test")
    void ancientEventTest(@NonNull final AncientMode ancientMode) throws IOException {

        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformContext platformContext = buildContext(ancientMode);
        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);

        final int stepsUntilAncient =
                ancientMode == GENERATION_THRESHOLD ? random.nextInt(50, 100) : random.nextInt(5, 10);
        final PcesSequencer sequencer = new DefaultPcesSequencer();
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultPcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);
        final AtomicLong latestDurableSequenceNumber = new AtomicLong();

        // We will add this event at the very end, it should be ancient by then
        final PlatformEvent ancientEvent = generator.generateEventWithoutIndex().getBaseEvent();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        writer.beginStreamingNewEvents();

        final Collection<PlatformEvent> rejectedEvents = new HashSet<>();

        long lowerBound = ancientMode.selectIndicator(0, 1);
        final Iterator<PlatformEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            final PlatformEvent event = iterator.next();

            sequencer.assignStreamSequenceNumber(event);
            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);

            lowerBound = Math.max(lowerBound, event.getAncientIndicator(ancientMode) - stepsUntilAncient);

            writer.updateNonAncientEventBoundary(new EventWindow(1, lowerBound, lowerBound, ancientMode));

            if (event.getAncientIndicator(ancientMode) < lowerBound) {
                // Although it's not common, it's actually possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                rejectedEvents.add(event);
                iterator.remove();
            }

            // request a flush sometimes
            if (random.nextInt(10) == 0) {
                passValueToDurabilityNexus(
                        writer.submitFlushRequest(event.getStreamSequenceNumber()), latestDurableSequenceNumber);
            }
        }

        passValueToDurabilityNexus(
                writer.submitFlushRequest(events.getLast().getStreamSequenceNumber()), latestDurableSequenceNumber);

        // Add the ancient event
        sequencer.assignStreamSequenceNumber(ancientEvent);
        if (lowerBound > ancientEvent.getAncientIndicator(ancientMode)) {
            // This is probably not possible... but just in case make sure this event is ancient
            try {
                writer.updateNonAncientEventBoundary(new EventWindow(
                        1,
                        ancientEvent.getAncientIndicator(ancientMode) + 1,
                        ancientEvent.getAncientIndicator(ancientMode) + 1,
                        ancientMode));
            } catch (final IllegalArgumentException e) {
                // ignore, more likely than not this event is way older than the actual ancient threshold
            }
        }

        passValueToDurabilityNexus(writer.writeEvent(ancientEvent), latestDurableSequenceNumber);
        rejectedEvents.add(ancientEvent);

        events.forEach(event -> assertTrue(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));
        rejectedEvents.forEach(
                event -> assertFalse(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));

        PcesWriterTestUtils.verifyStream(selfId, events, platformContext, 0, ancientMode);

        writer.closeCurrentMutableFile();
    }

    /**
     * In this test, keep adding events without increasing the first non-ancient threshold. This will force the
     * preferred span per file to eventually be reached and exceeded.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Overflow Test")
    void overflowTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformContext platformContext = buildContext(ancientMode);

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);
        final PcesSequencer sequencer = new DefaultPcesSequencer();
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultPcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);
        final AtomicLong latestDurableSequenceNumber = new AtomicLong();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        writer.beginStreamingNewEvents();

        for (final PlatformEvent event : events) {
            sequencer.assignStreamSequenceNumber(event);
            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);
        }

        passValueToDurabilityNexus(
                writer.submitFlushRequest(events.getLast().getStreamSequenceNumber()), latestDurableSequenceNumber);

        writer.closeCurrentMutableFile();

        PcesWriterTestUtils.verifyStream(selfId, events, platformContext, 0, ancientMode);

        // Without advancing the first non-ancient threshold,
        // we should never be able to increase the lower bound from 0.
        for (final Iterator<PcesFile> it = pcesFiles.getFileIterator(0, 0); it.hasNext(); ) {
            final PcesFile file = it.next();
            assertEquals(0, file.getLowerBound());
        }
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("beginStreamingEvents() Test")
    void beginStreamingEventsTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformContext platformContext = buildContext(ancientMode);

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);
        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesSequencer sequencer = new DefaultPcesSequencer();
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultPcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);
        final AtomicLong latestDurableSequenceNumber = new AtomicLong();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        // We intentionally do not call writer.beginStreamingNewEvents(). This should cause all events
        // passed into the writer to be more or less ignored.

        long lowerBound = ancientMode.selectIndicator(0, 1);
        for (final PlatformEvent event : events) {
            sequencer.assignStreamSequenceNumber(event);
            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);

            lowerBound = Math.max(lowerBound, event.getAncientIndicator(ancientMode) - stepsUntilAncient);
            writer.updateNonAncientEventBoundary(new EventWindow(1, lowerBound, lowerBound, ancientMode));
        }

        assertTrue(latestDurableSequenceNumber.get() >= events.getLast().getStreamSequenceNumber());

        // We shouldn't find any events in the stream.
        assertFalse(() ->
                pcesFiles.getFileIterator(PcesFileManager.NO_LOWER_BOUND, 0).hasNext());

        writer.closeCurrentMutableFile();
    }

    @ParameterizedTest
    @CsvSource({
        "GENERATION_THRESHOLD, true",
        "GENERATION_THRESHOLD, false",
        "BIRTH_ROUND_THRESHOLD, true",
        "BIRTH_ROUND_THRESHOLD, false"
    })
    @DisplayName("Discontinuity Test")
    void discontinuityTest(@NonNull final AncientMode ancientMode, final boolean truncateLastFile) throws IOException {
        beforeEach();

        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformContext platformContext = buildContext(ancientMode);

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);
        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesSequencer sequencer = new DefaultPcesSequencer();
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultPcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);
        final AtomicLong latestDurableSequenceNumber = new AtomicLong();

        final List<PlatformEvent> eventsBeforeDiscontinuity = new LinkedList<>();
        final List<PlatformEvent> eventsAfterDiscontinuity = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            final PlatformEvent event = generator.generateEventWithoutIndex().getBaseEvent();
            if (i < numEvents / 2) {
                eventsBeforeDiscontinuity.add(event);
            } else {
                eventsAfterDiscontinuity.add(event);
            }
        }

        writer.beginStreamingNewEvents();

        final Collection<PlatformEvent> rejectedEvents = new HashSet<>();

        long lowerBound = ancientMode.selectIndicator(0, 1);
        final Iterator<PlatformEvent> iterator1 = eventsBeforeDiscontinuity.iterator();
        while (iterator1.hasNext()) {
            final PlatformEvent event = iterator1.next();

            sequencer.assignStreamSequenceNumber(event);
            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);

            lowerBound = Math.max(lowerBound, event.getAncientIndicator(ancientMode) - stepsUntilAncient);
            writer.updateNonAncientEventBoundary(new EventWindow(1, lowerBound, lowerBound, ancientMode));

            if (event.getAncientIndicator(ancientMode) < lowerBound) {
                // Although it's not common, it's actually possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                rejectedEvents.add(event);
                iterator1.remove();
            }

            // request a flush sometimes
            if (random.nextInt(10) == 0) {
                passValueToDurabilityNexus(
                        writer.submitFlushRequest(event.getStreamSequenceNumber()), latestDurableSequenceNumber);
            }
        }

        passValueToDurabilityNexus(writer.registerDiscontinuity(100L), latestDurableSequenceNumber);
        eventsBeforeDiscontinuity.forEach(
                event -> assertTrue(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));

        if (truncateLastFile) {
            // Remove a single byte from the last file. This will corrupt the last event that was written.
            final Iterator<PcesFile> it = pcesFiles.getFileIterator(NO_LOWER_BOUND, 0);
            while (it.hasNext()) {
                final PcesFile file = it.next();
                if (!it.hasNext()) {
                    FileManipulation.truncateNBytesFromFile(file.getPath(), 1);
                }
            }

            eventsBeforeDiscontinuity.remove(eventsBeforeDiscontinuity.size() - 1);
        }

        final Iterator<PlatformEvent> iterator2 = eventsAfterDiscontinuity.iterator();
        while (iterator2.hasNext()) {
            final PlatformEvent event = iterator2.next();

            sequencer.assignStreamSequenceNumber(event);
            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);

            lowerBound = Math.max(lowerBound, event.getAncientIndicator(ancientMode) - stepsUntilAncient);
            writer.updateNonAncientEventBoundary(new EventWindow(1, lowerBound, lowerBound, ancientMode));

            if (event.getAncientIndicator(ancientMode) < lowerBound) {
                // Although it's not common, it's actually possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                rejectedEvents.add(event);
                iterator2.remove();
            }

            // request a flush sometimes
            if (random.nextInt(10) == 0) {
                passValueToDurabilityNexus(
                        writer.submitFlushRequest(event.getStreamSequenceNumber()), latestDurableSequenceNumber);
            }
        }

        passValueToDurabilityNexus(
                writer.submitFlushRequest(eventsAfterDiscontinuity.getLast().getStreamSequenceNumber()),
                latestDurableSequenceNumber);

        assertTrue(latestDurableSequenceNumber.get()
                >= eventsAfterDiscontinuity
                        .get(eventsAfterDiscontinuity.size() - 1)
                        .getStreamSequenceNumber());
        eventsAfterDiscontinuity.forEach(
                event -> assertTrue(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));
        rejectedEvents.forEach(
                event -> assertFalse(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));

        PcesWriterTestUtils.verifyStream(
                selfId, eventsBeforeDiscontinuity, platformContext, truncateLastFile ? 1 : 0, ancientMode);

        writer.closeCurrentMutableFile();
    }

    /**
     * In this test, increase the lower bound as events are added. When this happens, we should never have to include
     * more than the preferred number of events in each file.
     */
    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Advance Non Ancient Boundary Test")
    void advanceNonAncientBoundaryTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PlatformContext platformContext = buildContext(ancientMode);
        final FakeTime time = (FakeTime) platformContext.getTime();

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);
        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesSequencer sequencer = new DefaultPcesSequencer();
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultPcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);
        final AtomicLong latestDurableSequenceNumber = new AtomicLong(-1);

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        writer.beginStreamingNewEvents();

        final Set<PlatformEvent> rejectedEvents = new HashSet<>();

        long lowerBound = ancientMode.selectIndicator(0, 1);
        for (final PlatformEvent event : events) {
            sequencer.assignStreamSequenceNumber(event);

            passValueToDurabilityNexus(writer.writeEvent(event), latestDurableSequenceNumber);
            assertFalse(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber());

            time.tick(Duration.ofSeconds(1));

            if (event.getAncientIndicator(ancientMode) < lowerBound) {
                // This event is ancient and will have been rejected.
                rejectedEvents.add(event);
            }

            lowerBound = Math.max(lowerBound, event.getAncientIndicator(ancientMode) - stepsUntilAncient);
            writer.updateNonAncientEventBoundary(new EventWindow(1, lowerBound, lowerBound, ancientMode));

            // request a flush sometimes
            if (random.nextInt(10) == 0) {
                passValueToDurabilityNexus(
                        writer.submitFlushRequest(event.getStreamSequenceNumber()), latestDurableSequenceNumber);
            }
        }

        passValueToDurabilityNexus(
                writer.submitFlushRequest(events.getLast().getStreamSequenceNumber()), latestDurableSequenceNumber);

        // Remove the rejected events from the list
        events.removeIf(rejectedEvents::contains);

        events.forEach(event -> assertTrue(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));
        rejectedEvents.forEach(
                event -> assertFalse(latestDurableSequenceNumber.get() >= event.getStreamSequenceNumber()));
        PcesWriterTestUtils.verifyStream(selfId, events, platformContext, 0, ancientMode);

        // Advance the time so that all files are GC eligible according to the clock.
        time.tick(Duration.ofDays(1));

        // Prune old files.
        final long lowerBoundToStore = events.getLast().getAncientIndicator(ancientMode) / 2;
        writer.setMinimumAncientIdentifierToStore(lowerBoundToStore);

        // We shouldn't see any files that are incapable of storing events above the minimum
        final PcesFileTracker pcesFiles2 = PcesFileReader.readFilesFromDisk(
                platformContext, PcesUtilities.getDatabaseDirectory(platformContext, selfId), 0, false, ancientMode);

        pcesFiles2
                .getFileIterator(NO_LOWER_BOUND, 0)
                .forEachRemaining(file -> assertTrue(file.getUpperBound() >= lowerBoundToStore));

        writer.closeCurrentMutableFile();

        // Since we were very careful to always advance the first non-ancient threshold, we should
        // find lots of files with a lower bound exceeding 0.
        boolean foundNonZeroBoundary = false;
        for (final Iterator<PcesFile> fileIterator = pcesFiles2.getFileIterator(0, 0); fileIterator.hasNext(); ) {
            final PcesFile file = fileIterator.next();
            if (file.getLowerBound() > 0) {
                foundNonZeroBoundary = true;
                break;
            }
        }
        assertTrue(foundNonZeroBoundary);
    }

    @ParameterizedTest
    @MethodSource("buildArguments")
    @DisplayName("Flush request test")
    void flushRequestTest(@NonNull final AncientMode ancientMode) throws IOException {
        final Randotron r = Randotron.create();
        final PlatformContext platformContext = buildContext(ancientMode);
        final PcesFileManager fileManager =
                new PcesFileManager(platformContext, new PcesFileTracker(ancientMode), selfId, 0);
        final PcesWriter writer = new DefaultPcesWriter(platformContext, fileManager);

        writer.beginStreamingNewEvents();

        final List<PlatformEvent> events = new ArrayList<>();
        for (long i = 0; i < 9; i++) {
            final PlatformEvent event = new TestingEventBuilder(r).build();
            event.setStreamSequenceNumber(i);
            events.add(event);
        }

        assertNull(writer.submitFlushRequest(1L), "No event has been written to flush");
        assertEquals(
                1,
                writer.writeEvent(events.get(1)),
                "Writing an event with a sequence number already requested to flush should flush immediately");
        assertNull(
                writer.writeEvent(events.get(2)),
                "Writing an event with sequence number not requested to flush should not flush");
        assertEquals(
                2,
                writer.submitFlushRequest(2L),
                "Requesting a flush for a sequence number already written should flush immediately");
        assertNull(writer.submitFlushRequest(4L), "No event has been written to flush");
        assertNull(
                writer.writeEvent(events.get(3)),
                "Pending flush request for a later sequence number shouldn't cause a flush");
        assertNull(
                writer.submitFlushRequest(5L), "New flush request for a later sequence number shouldn't cause a flush");
        assertEquals(
                5,
                writer.writeEvent(events.get(5)),
                "Intermediate flushes of a lower sequence number shouldn't hinder a later flush request");
        assertNull(writer.submitFlushRequest(6L), "No event has been written to flush");
        assertNull(writer.submitFlushRequest(8L), "No event has been written to flush");
        assertEquals(
                7,
                writer.writeEvent(events.get(7)),
                "Pending flush request for an earlier sequence number should cause a flush");
        assertEquals(
                8, writer.writeEvent(events.get(8)), "Flush requests for later sequences numbers should be maintained");
    }
}
