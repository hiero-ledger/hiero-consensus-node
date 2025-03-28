// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery;

import static com.swirlds.platform.recovery.RecoveryTestUtils.generateRandomEvents;
import static com.swirlds.platform.recovery.RecoveryTestUtils.getFirstEventStreamFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.truncateFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.writeRandomEventStream;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.hiero.consensus.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.platform.recovery.internal.ObjectStreamIterator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.event.CesEvent;
import org.hiero.consensus.model.io.SelfSerializable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ObjectStreamIterator Test")
class ObjectStreamIteratorTest {

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero.consensus");
    }

    public static void assertEventsAreEqual(final CesEvent expected, final CesEvent actual) {
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Simple Stream Test")
    public void simpleStreamTest() throws IOException, NoSuchAlgorithmException {
        // FUTURE WORK: once streaming code is simplified, rewrite this test to use simple object types

        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);

        try (final IOIterator<SelfSerializable> iterator = new ObjectStreamIterator<>(eventStreamFile, false)) {
            assertTrue(iterator.next() instanceof Hash, "expected first object to be a hash");

            boolean lastHashFound = false;
            int eventIndex = 0;

            while (iterator.hasNext()) {
                final SelfSerializable peekObject = iterator.peek();
                final SelfSerializable object = iterator.next();
                assertSame(object, peekObject, "invalid peek behavior");

                if (object instanceof final Hash hash) {
                    lastHashFound = true;
                    assertFalse(iterator.hasNext(), "there should be no objects after the last hash");
                } else if (object instanceof CesEvent e) {
                    assertEventsAreEqual(e, events.get(eventIndex));
                    eventIndex++;
                } else {
                    fail("Object of type " + object.getClass() + " was not expected");
                }
            }

            assertTrue(lastHashFound, "hash at end of the file not found");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Empty Stream Test")
    void emptyStreamTest() throws IOException {
        // With zero bytes, if we don't tolerate partial files then this will throw
        assertThrows(IOException.class, () -> new ObjectStreamIterator<>(new ByteArrayInputStream(new byte[0]), false));
    }

    @Test
    @DisplayName("Empty Stream Allow Partial Files Test")
    void emptyStreamAllowPartialFileTest() throws IOException {
        final IOIterator<SelfSerializable> iterator =
                new ObjectStreamIterator<>(new ByteArrayInputStream(new byte[0]), true);

        assertFalse(iterator.hasNext(), "no objects are in the stream");
    }

    @Test
    @DisplayName("Allowed Truncated File Test")
    public void allowedTruncatedFileTest() throws IOException, NoSuchAlgorithmException {
        // FUTURE WORK: once streaming code is simplified, rewrite this test to use simple object types

        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);
        final int validObjectCount = truncateFile(eventStreamFile, false);

        try (final IOIterator<SelfSerializable> iterator = new ObjectStreamIterator<>(eventStreamFile, true)) {
            assertTrue(iterator.next() instanceof Hash, "expected first object to be a hash");

            boolean lastHashFound = false;
            int eventIndex = 0;

            int count = 1;
            while (iterator.hasNext()) {
                count++;
                final SelfSerializable peekObject = iterator.peek();
                final SelfSerializable object = iterator.next();
                assertSame(object, peekObject, "invalid peek behavior");

                if (object instanceof final Hash hash) {
                    lastHashFound = true;
                    assertFalse(iterator.hasNext(), "there should be no objects after the last hash");
                } else if (object instanceof CesEvent e) {
                    assertEventsAreEqual(e, events.get(eventIndex));
                    eventIndex++;
                } else {
                    fail("Object of type " + object.getClass() + " was not expected");
                }
            }

            assertEquals(validObjectCount, count, "unexpected number of objects returned");

            assertFalse(lastHashFound, "we should have ended before the last hash was found");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Disallowed Truncated File Test")
    public void disallowedTruncatedFileTest() throws IOException, NoSuchAlgorithmException {
        // FUTURE WORK: once streaming code is simplified, rewrite this test to use simple object types

        final Random random = getRandomPrintSeed();
        final Path directory = LegacyTemporaryFileBuilder.buildTemporaryDirectory(CONFIGURATION);

        final List<CesEvent> events = generateRandomEvents(random, 0L, Duration.ofSeconds(4), 1, 20);

        writeRandomEventStream(random, directory, 2, events);
        final Path eventStreamFile = getFirstEventStreamFile(directory);
        final int validObjectCount = truncateFile(eventStreamFile, false);

        boolean exceptionThrown = false;
        int count = 1;
        try (final IOIterator<SelfSerializable> iterator = new ObjectStreamIterator<>(eventStreamFile, false)) {
            assertTrue(iterator.next() instanceof Hash, "expected first object to be a hash");

            boolean lastHashFound = false;
            int eventIndex = 0;

            while (iterator.hasNext()) {
                count++;
                final SelfSerializable peekObject = iterator.peek();
                final SelfSerializable object = iterator.next();
                assertSame(object, peekObject, "invalid peek behavior");

                if (object instanceof final Hash hash) {
                    lastHashFound = true;
                    assertFalse(iterator.hasNext(), "there should be no objects after the last hash");
                } else if (object instanceof CesEvent e) {
                    assertEventsAreEqual(e, events.get(eventIndex));
                    eventIndex++;
                } else {
                    fail("Object of type " + object.getClass() + " was not expected");
                }
            }

            assertFalse(lastHashFound, "we should have ended before the last hash was found");

            assertThrows(NoSuchElementException.class, iterator::next, "no objects should remain");
            assertThrows(NoSuchElementException.class, iterator::peek, "no objects should remain");

        } catch (final IOException ioException) {
            assertEquals(validObjectCount, count, "exception thrown at wrong place");
            exceptionThrown = true;
        } finally {
            FileUtils.deleteDirectory(directory);
        }

        assertTrue(exceptionThrown, "exception should have been thrown");
    }
}
