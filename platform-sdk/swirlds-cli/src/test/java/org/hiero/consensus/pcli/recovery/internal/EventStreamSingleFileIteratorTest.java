// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.recovery.internal;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.hiero.consensus.event.stream.test.fixtures.EventStreamTestUtils.generateRandomEvents;
import static org.hiero.consensus.event.stream.test.fixtures.EventStreamTestUtils.getFirstEventStreamFile;
import static org.hiero.consensus.event.stream.test.fixtures.EventStreamTestUtils.writeRandomEventStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.file.FileUtils;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.model.event.CesEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("EventStreamSingleFileIterator Test")
class EventStreamSingleFileIteratorTest {

    @TempDir
    private Path directory;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        ConstructableRegistration.registerAllConstructables();
    }

    public static void assertEventsAreEqual(final CesEvent expected, final CesEvent actual) {
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Simple Stream Test")
    void simpleStreamTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);

        try (final EventStreamSingleFileIterator iterator = new EventStreamSingleFileIterator(eventStreamFile, false)) {
            assertNotNull(iterator.getStartHash(), "starting hash should be known");
            assertNull(iterator.getEndHash(), "end hash should not yet be known");

            int eventIndex = 0;

            while (iterator.hasNext()) {
                final CesEvent peekObject = iterator.peek();
                final CesEvent event = iterator.next();
                assertSame(event, peekObject, "invalid peek behavior");
                assertEventsAreEqual(event, events.get(eventIndex));
                eventIndex++;
            }

            assertNotNull(iterator.getEndHash(), "end hash should now be known");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Disabled("this test is flaky")
    @Test
    @DisplayName("Allowed Truncated File Test")
    void allowedTruncatedFileTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);

        StreamFileTestUtils.truncateFile(eventStreamFile, false);

        try (final EventStreamSingleFileIterator iterator = new EventStreamSingleFileIterator(eventStreamFile, true)) {
            assertNotNull(iterator.getStartHash(), "starting hash should be known");
            assertNull(iterator.getEndHash(), "end hash should not yet be known");

            int eventIndex = 0;

            while (iterator.hasNext()) {
                final CesEvent peekObject = iterator.peek();
                final CesEvent event = iterator.next();
                assertSame(event, peekObject, "invalid peek behavior");
                assertEquals(event, events.get(eventIndex), "event should match input event");
                eventIndex++;
            }

            assertNull(iterator.getEndHash(), "file was truncated, hash should not be found");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Disallowed Truncated File Test")
    void disallowedTruncatedFileTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);

        final int validObjectCount = StreamFileTestUtils.truncateFile(eventStreamFile, false);

        boolean exceptionThrown = false;
        int count = 0;
        try (final EventStreamSingleFileIterator iterator = new EventStreamSingleFileIterator(eventStreamFile, false)) {
            assertNotNull(iterator.getStartHash(), "starting hash should be known");
            assertNull(iterator.getEndHash(), "end hash should not yet be known");

            int eventIndex = 0;

            while (iterator.hasNext()) {
                count++;
                final CesEvent peekObject = iterator.peek();
                final CesEvent event = iterator.next();
                assertSame(event, peekObject, "invalid peek behavior");
                assertEventsAreEqual(event, events.get(eventIndex));
                eventIndex++;
            }

            assertNull(iterator.getEndHash(), "file was truncated, hash should not be found");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } catch (final IOException ioException) {
            assertEquals(validObjectCount - 1, count, "unexpected number of events read");
            exceptionThrown = true;
        } finally {
            FileUtils.deleteDirectory(directory);
        }

        assertTrue(exceptionThrown, "expecting read of truncated file to fail");
    }

    @Test
    @DisplayName("Disallowed Truncated File Test")
    void disallowedTruncatedOnBoundaryTest() throws IOException {
        final Random random = getRandomPrintSeed();

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);

        final int validObjectCount = StreamFileTestUtils.truncateFile(eventStreamFile, true);

        boolean exceptionThrown = false;
        int count = 0;
        try (final EventStreamSingleFileIterator iterator = new EventStreamSingleFileIterator(eventStreamFile, false)) {
            assertNotNull(iterator.getStartHash(), "starting hash should be known");
            assertNull(iterator.getEndHash(), "end hash should not yet be known");

            int eventIndex = 0;

            while (iterator.hasNext()) {
                count++;
                final CesEvent peekObject = iterator.peek();
                final CesEvent event = iterator.next();
                assertSame(event, peekObject, "invalid peek behavior");
                assertEventsAreEqual(event, events.get(eventIndex));
                eventIndex++;
            }

            assertNull(iterator.getEndHash(), "file was truncated, hash should not be found");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } catch (final IOException ioException) {
            assertEquals(validObjectCount - 1, count, "unexpected number of events read");
            exceptionThrown = true;
        } finally {
            FileUtils.deleteDirectory(directory);
        }

        assertTrue(exceptionThrown, "expecting read of truncated file to fail");
    }
}
