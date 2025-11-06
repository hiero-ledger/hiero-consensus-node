// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.test.fixtures.io.FileManipulation.corruptFile;
import static com.swirlds.common.test.fixtures.io.FileManipulation.truncateFile;
import static com.swirlds.platform.consensus.ConsensusTestArgs.DEFAULT_PLATFORM_CONTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.junit.extensions.ParamName;
import org.hiero.junit.extensions.ParamSource;
import org.hiero.junit.extensions.ParameterCombinationExtension;
import org.hiero.junit.extensions.UseParameterSources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PCES Read Write Tests")
class PcesReadWriteTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        Files.createDirectories(testDirectory);
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
    }

    @SuppressWarnings("unused") // This method is used as a parameter source in tests
    protected static Iterable<Boolean> booleanArguments() {
        return List.of(Boolean.TRUE, Boolean.FALSE);
    }

    /**
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Write Then Read Test")
    void writeThenReadTest(@ParamName("pcesFileWriterType") final PcesFileWriterType pcesFileWriterType)
            throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        upperBound += random.nextInt(0, 10);

        final PcesFile file =
                PcesFile.of(RandomUtils.randomInstant(random), random.nextInt(0, 100), 0, upperBound, 0, testDirectory);

        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);
        for (final PlatformEvent event : events) {
            mutableFile.writeEvent(event);
        }

        mutableFile.close();

        final IOIterator<PlatformEvent> iterator = file.iterator(Long.MIN_VALUE);
        final List<PlatformEvent> deserializedEvents = new ArrayList<>();
        iterator.forEachRemaining(deserializedEvents::add);
        assertEquals(events.size(), deserializedEvents.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i), deserializedEvents.get(i));
        }
    }

    /**
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Read Files After Minimum Test")
    void readFilesAfterMinimumTest(@ParamName("pcesFileWriterType") final PcesFileWriterType pcesFileWriterType)
            throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        final long middle = upperBound / 2;

        upperBound += random.nextInt(0, 10);

        final PcesFile file = PcesFile.of(
                RandomUtils.randomInstant(random), random.nextInt(0, 100), 0, upperBound, upperBound, testDirectory);

        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);
        for (final PlatformEvent event : events) {
            mutableFile.writeEvent(event);
        }

        mutableFile.close();

        final IOIterator<PlatformEvent> iterator = file.iterator(middle);
        final List<PlatformEvent> deserializedEvents = new ArrayList<>();
        iterator.forEachRemaining(deserializedEvents::add);

        // We don't want any events with an ancient indicator less than the middle
        events.removeIf(event -> event.getBirthRound() < middle);

        assertEquals(events.size(), deserializedEvents.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i), deserializedEvents.get(i));
        }
    }

    @Test
    @DisplayName("Read Empty File Test")
    void readEmptyFileTest() throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final PcesFile file = PcesFile.of(
                RandomUtils.randomInstant(random),
                random.nextInt(0, 100),
                random.nextLong(0, 1000),
                random.nextLong(1000, 2000),
                0,
                testDirectory);

        final PcesMutableFile mutableFile = file.getMutableFile(PcesFileWriterType.OUTPUT_STREAM);
        mutableFile.close();

        final IOIterator<PlatformEvent> iterator = file.iterator(Long.MIN_VALUE);
        assertFalse(iterator.hasNext());
    }

    /**
     * @param truncateOnBoundary {@link PcesReadWriteTests#booleanArguments()}
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(param = "truncateOnBoundary", method = "booleanArguments"),
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Truncated Event Test")
    void truncatedEventTest(
            final @ParamName("truncateOnBoundary") boolean truncateOnBoundary,
            final @ParamName("pcesFileWriterType") PcesFileWriterType pcesFileWriterType)
            throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        upperBound += random.nextInt(0, 10);

        final PcesFile file = PcesFile.of(
                RandomUtils.randomInstant(random), random.nextInt(0, 100), 0, upperBound, upperBound, testDirectory);

        final Map<Integer /* event index */, Integer /* last byte position */> byteBoundaries = new HashMap<>();

        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);
        for (int i = 0; i < events.size(); i++) {
            final PlatformEvent event = events.get(i);
            mutableFile.writeEvent(event);
            byteBoundaries.put(i, (int) mutableFile.fileSize());
        }

        mutableFile.close();

        final int lastEventIndex =
                random.nextInt(0, events.size() - 2 /* make sure we always truncate at least one event */);

        final int truncationPosition = byteBoundaries.get(lastEventIndex) + (truncateOnBoundary ? 0 : 1);

        truncateFile(file.getPath(), truncationPosition);

        final PcesFileIterator iterator = file.iterator(Long.MIN_VALUE);
        final List<PlatformEvent> deserializedEvents = new ArrayList<>();

        iterator.forEachRemaining(deserializedEvents::add);

        assertEquals(truncateOnBoundary, !iterator.hasPartialEvent());

        assertEquals(lastEventIndex + 1, deserializedEvents.size());

        for (int i = 0; i < deserializedEvents.size(); i++) {
            assertEquals(events.get(i), deserializedEvents.get(i));
        }
    }

    /**
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(param = "truncateOnBoundary", method = "booleanArguments"),
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Corrupted Events Test")
    void corruptedEventsTest(final @ParamName("pcesFileWriterType") PcesFileWriterType pcesFileWriterType)
            throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        upperBound += random.nextInt(0, 10);

        final PcesFile file =
                PcesFile.of(RandomUtils.randomInstant(random), random.nextInt(0, 100), 0, upperBound, 0, testDirectory);

        final Map<Integer /* event index */, Integer /* last byte position */> byteBoundaries = new HashMap<>();

        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);
        for (int i = 0; i < events.size(); i++) {
            final PlatformEvent event = events.get(i);
            mutableFile.writeEvent(event);
            byteBoundaries.put(i, (int) mutableFile.fileSize());
        }

        mutableFile.close();

        final int lastEventIndex =
                random.nextInt(0, events.size() - 2 /* make sure we always corrupt at least one event */);

        final int corruptionPosition = byteBoundaries.get(lastEventIndex);

        corruptFile(random, file.getPath(), corruptionPosition);

        final PcesFileIterator iterator = file.iterator(Long.MIN_VALUE);

        for (int i = 0; i <= lastEventIndex; i++) {
            assertEquals(events.get(i), iterator.next());
        }

        assertThrows(Exception.class, iterator::next);
    }

    /**
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(param = "truncateOnBoundary", method = "booleanArguments"),
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Write Invalid Event Test")
    void writeInvalidEventTest(final @ParamName("pcesFileWriterType") PcesFileWriterType pcesFileWriterType)
            throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed();

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long lowerBound = Long.MAX_VALUE;
        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            lowerBound = Math.min(lowerBound, event.getBirthRound());
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        // Intentionally choose minimum and maximum boundaries that do not permit all generated events
        final long restrictedLowerBound = lowerBound + (lowerBound + upperBound) / 4;
        final long restrictedUpperBound = upperBound - (lowerBound + upperBound) / 4;

        final PcesFile file = PcesFile.of(
                RandomUtils.randomInstant(random),
                random.nextInt(0, 100),
                restrictedLowerBound,
                restrictedUpperBound,
                0,
                testDirectory);
        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);

        final List<PlatformEvent> validEvents = new ArrayList<>();
        for (final PlatformEvent event : events) {
            if (event.getBirthRound() >= restrictedLowerBound && event.getBirthRound() <= restrictedUpperBound) {
                mutableFile.writeEvent(event);
                validEvents.add(event);
            } else {
                assertThrows(IllegalStateException.class, () -> mutableFile.writeEvent(event));
            }
        }

        mutableFile.close();

        final IOIterator<PlatformEvent> iterator = file.iterator(Long.MIN_VALUE);
        for (final PlatformEvent event : validEvents) {
            assertTrue(iterator.hasNext());
            assertEquals(event, iterator.next());
        }
        assertFalse(iterator.hasNext());
    }

    /**
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(param = "truncateOnBoundary", method = "booleanArguments"),
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Span Compression Test")
    void spanCompressionTest(final @ParamName("pcesFileWriterType") PcesFileWriterType pcesFileWriterType)
            throws IOException {
        final Random random = RandomUtils.getRandomPrintSeed(0);

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long lowerBound = Long.MAX_VALUE;
        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            lowerBound = Math.min(lowerBound, event.getBirthRound());
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        upperBound += random.nextInt(1, 10);

        final PcesFile file = PcesFile.of(
                RandomUtils.randomInstant(random), random.nextInt(0, 100), lowerBound, upperBound, 0, testDirectory);

        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);
        for (final PlatformEvent event : events) {
            mutableFile.writeEvent(event);
        }

        mutableFile.close();
        final PcesFile compressedFile = mutableFile.compressSpan(0);

        assertEquals(file.getPath().getParent(), compressedFile.getPath().getParent());
        assertEquals(file.getSequenceNumber(), compressedFile.getSequenceNumber());
        assertEquals(file.getLowerBound(), compressedFile.getLowerBound());
        assertTrue(upperBound > compressedFile.getUpperBound());
        assertEquals(mutableFile.getUtilizedSpan(), compressedFile.getUpperBound() - compressedFile.getLowerBound());
        assertNotEquals(file.getPath(), compressedFile.getPath());
        assertNotEquals(file.getUpperBound(), compressedFile.getUpperBound());
        assertTrue(Files.exists(compressedFile.getPath()));
        assertFalse(Files.exists(file.getPath()));

        final IOIterator<PlatformEvent> iterator = compressedFile.iterator(Long.MIN_VALUE);
        final List<PlatformEvent> deserializedEvents = new ArrayList<>();
        iterator.forEachRemaining(deserializedEvents::add);
        assertEquals(events.size(), deserializedEvents.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i), deserializedEvents.get(i));
        }
    }

    /**
     * @param pcesFileWriterType PCesFileWriterType.values()
     */
    @TestTemplate
    @ExtendWith(ParameterCombinationExtension.class)
    @UseParameterSources({
        @ParamSource(param = "truncateOnBoundary", method = "booleanArguments"),
        @ParamSource(
                param = "pcesFileWriterType",
                fullyQualifiedClass = "com.swirlds.platform.event.preconsensus.PcesFileWriterType",
                method = "values")
    })
    @DisplayName("Partial Span Compression Test")
    void partialSpanCompressionTest(final @ParamName("pcesFileWriterType") PcesFileWriterType pcesFileWriterType)
            throws IOException {

        final Random random = RandomUtils.getRandomPrintSeed(0);

        final int numEvents = 100;

        final StandardGraphGenerator generator = new StandardGraphGenerator(
                DEFAULT_PLATFORM_CONTEXT,
                random.nextLong(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource(),
                new StandardEventSource());

        final List<PlatformEvent> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEvent().getBaseEvent());
        }

        long lowerBound = Long.MAX_VALUE;
        long upperBound = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            lowerBound = Math.min(lowerBound, event.getBirthRound());
            upperBound = Math.max(upperBound, event.getBirthRound());
        }

        final long maximumFileBoundary = upperBound + random.nextInt(10, 20);
        final long uncompressedSpan = 5;

        final PcesFile file = PcesFile.of(
                RandomUtils.randomInstant(random),
                random.nextInt(0, 100),
                lowerBound,
                maximumFileBoundary,
                0,
                testDirectory);

        final PcesMutableFile mutableFile = file.getMutableFile(pcesFileWriterType);
        for (final PlatformEvent event : events) {
            mutableFile.writeEvent(event);
        }

        mutableFile.close();
        final PcesFile compressedFile = mutableFile.compressSpan(upperBound + uncompressedSpan);

        assertEquals(file.getPath().getParent(), compressedFile.getPath().getParent());
        assertEquals(file.getSequenceNumber(), compressedFile.getSequenceNumber());
        assertEquals(file.getLowerBound(), compressedFile.getLowerBound());
        assertEquals(upperBound + uncompressedSpan, compressedFile.getUpperBound());
        assertEquals(
                mutableFile.getUtilizedSpan(),
                compressedFile.getUpperBound() - compressedFile.getLowerBound() - uncompressedSpan);
        assertNotEquals(file.getPath(), compressedFile.getPath());
        assertNotEquals(file.getUpperBound(), compressedFile.getUpperBound());
        assertTrue(Files.exists(compressedFile.getPath()));
        assertFalse(Files.exists(file.getPath()));

        final IOIterator<PlatformEvent> iterator = compressedFile.iterator(Long.MIN_VALUE);
        final List<PlatformEvent> deserializedEvents = new ArrayList<>();
        iterator.forEachRemaining(deserializedEvents::add);
        assertEquals(events.size(), deserializedEvents.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i), deserializedEvents.get(i));
        }
    }

    @Test
    @DisplayName("Empty File Test")
    void emptyFileTest() throws IOException {
        final PcesFile file = PcesFile.of(Instant.now(), 0, 0, 100, 0, testDirectory);

        final Path path = file.getPath();

        Files.createDirectories(path.getParent());
        assertTrue(path.toFile().createNewFile());
        assertTrue(Files.exists(path));

        try (final PcesFileIterator iterator = file.iterator(Long.MIN_VALUE)) {
            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
        }
    }
}
