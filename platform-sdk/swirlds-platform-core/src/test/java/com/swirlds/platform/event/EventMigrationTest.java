// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.recovery.internal.EventStreamSingleFileIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class EventMigrationTest {

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("");
    }

    public static Stream<Arguments> migrationTestArguments() {
        return Stream.of(Arguments.of(FileType.PCES, "eventFiles/release64/", 321, 2));
    }

    /**
     * Tests the migration of events. The main thing we are testing is that the hashes of old events can still be
     * calculated when the code changes. This is done by calculating the hashes of the events that are read and matching
     * them to the parent descriptors inside the events. The parents of most events will be present in the file, except
     * for a few events at the beginning of the file.
     */
    @ParameterizedTest
    @MethodSource("migrationTestArguments")
    public void migration(
            @NonNull final FileType fileType,
            @NonNull final String fileName,
            final int numEventsExpected,
            final int unmatchedHashesExpected)
            throws URISyntaxException, IOException {
        final Set<Hash> eventHashes = new HashSet<>();
        final Set<Hash> parentHashes = new HashSet<>();

        final URL resource = this.getClass().getClassLoader().getResource(fileName);
        Assertions.assertNotNull(resource, "Resource not found: " + fileName);
        final Path path = new File(resource.toURI()).toPath();

        // read the events
        final List<PlatformEvent> eventsRead = new ArrayList<>();
        switch (fileType) {
            case CES -> {
                try (final EventStreamSingleFileIterator iterator = new EventStreamSingleFileIterator(path, false)) {
                    while (iterator.hasNext()) {
                        eventsRead.add(iterator.next().getPlatformEvent());
                    }
                }
            }
            case PCES -> {
                final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                        new TestConfigBuilder()
                                .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                                .getOrCreateConfig(),
                        new NoOpRecycleBin(),
                        path,
                        0L,
                        false);
                try (final IOIterator<PlatformEvent> iterator = fileTracker.getEventIterator(0l, 0l)) {
                    while (iterator.hasNext()) {
                        eventsRead.add(iterator.next());
                    }
                }
            }
        }

        // hash events
        final DefaultEventHasher hasher = new DefaultEventHasher();
        eventsRead.forEach(hasher::hashEvent);

        // store the hashes of the events and their parents
        eventsRead.stream().map(PlatformEvent::getHash).forEach(eventHashes::add);
        eventsRead.stream()
                .map(PlatformEvent::getAllParents)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .map(EventDescriptorWrapper::hash)
                .forEach(parentHashes::add);

        // assertions
        Assertions.assertEquals(
                numEventsExpected,
                eventsRead.size(),
                "this file is expected to have %d events but has %d".formatted(numEventsExpected, eventsRead.size()));
        Assertions.assertEquals(
                numEventsExpected,
                eventHashes.size(),
                "we expected to have %d hashes (one for each event) but have %d"
                        .formatted(numEventsExpected, eventHashes.size()));
        eventHashes.removeAll(parentHashes);
        Assertions.assertEquals(
                unmatchedHashesExpected,
                eventHashes.size(),
                "the hashes of most parents are expected to match the hashes of events."
                        + " Number of unmatched hashes: " + eventHashes.size());
    }

    enum FileType {
        PCES,
        CES
    }
}
