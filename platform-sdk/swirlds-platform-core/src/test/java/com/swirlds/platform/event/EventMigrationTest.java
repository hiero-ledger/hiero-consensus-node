// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event;

import com.swirlds.platform.recovery.internal.EventStreamSingleFileIterator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
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
        return Stream.of(Arguments.of("eventFiles/testnet-59/2025-02-14T00_00_00.005716199Z.evts", 622, 119));
    }

    /**
     * Tests the migration of events. The main thing we are testing is that the
     * hashes of old events can still be calculated when the code changes. This is done by calculating the hashes of the
     * events that are read and matching them to the parent descriptors inside the events. The parents of most events
     * will be present in the file, except for a few events at the beginning of the file.
     */
    @ParameterizedTest
    @MethodSource("migrationTestArguments")
    public void migration(
            @NonNull final String fileName, final int numEventsExpected, final int unmatchedHashesExpected)
            throws URISyntaxException, IOException {
        final Set<Hash> eventHashes = new HashSet<>();
        final Set<Hash> parentHashes = new HashSet<>();
        int numEventsFound = 0;

        try (final EventStreamSingleFileIterator iterator = new EventStreamSingleFileIterator(
                new File(this.getClass().getClassLoader().getResource(fileName).toURI()).toPath(), false)) {
            while (iterator.hasNext()) {
                final PlatformEvent platformEvent = iterator.next().getPlatformEvent();
                new DefaultEventHasher().hashEvent(platformEvent);
                numEventsFound++;
                eventHashes.add(platformEvent.getHash());
                platformEvent.getAllParents().stream()
                        .filter(Objects::nonNull)
                        .map(EventDescriptorWrapper::hash)
                        .forEach(parentHashes::add);
            }
        }

        Assertions.assertEquals(
                numEventsExpected,
                numEventsFound,
                "this file is expected to have %d events but has %d".formatted(numEventsExpected, numEventsFound));
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
}
