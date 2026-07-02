// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import static org.hiero.consensus.roster.test.fixtures.RosterFactory.generateRoster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pcli.graph.utils.TestEventUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PcesEventGraphSource}.
 */
class PcesEventGraphSourceTest {

    private static final int START_NODE_ID = 0;
    private static final int END_NODE_ID = 4;
    private static final List<NodeId> NODE_IDS =
            IntStream.range(START_NODE_ID, END_NODE_ID).mapToObj(NodeId::of).toList();
    public static final int NUM_EVENTS = 1000;

    @TempDir
    private Path baseDir;

    private Path pcesLocation;

    @BeforeEach
    void setUp() throws IOException, KeyStoreException, ExecutionException, InterruptedException {
        pcesLocation = baseDir.resolve(Path.of("preconsensus-events"));
        final PlatformContext context =
                PlatformContextFactory.createPlatformContext(Function.identity(), Function.identity());
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = KeysAndCertsGenerator.generateKeysAndCerts(NODE_IDS);
        final Roster roster = generateRoster(keysAndCertsMap);
        TestEventUtils.generatePreConsensusStream(context, pcesLocation, roster, keysAndCertsMap, NUM_EVENTS);
    }

    @Test
    void sourceHasNextAndNextWorks() {
        final PlatformContext context =
                PlatformContextFactory.createPlatformContext(Function.identity(), Function.identity());

        final PcesEventGraphSource source = new PcesEventGraphSource(pcesLocation, context);

        int i = 0;
        while (i++ < NUM_EVENTS) {
            assertTrue(source.hasNext(), "Expected to have events");
            source.next();
        }
        assertFalse(source.hasNext());
        Assertions.assertThrows(NoSuchElementException.class, source::next);
    }

    @Test
    void sourceReturnsAllEvents() {
        final PlatformContext context =
                PlatformContextFactory.createPlatformContext(Function.identity(), Function.identity());

        final PcesEventGraphSource source = new PcesEventGraphSource(pcesLocation, context);

        final List<PlatformEvent> allEvents = new ArrayList<>();
        source.forEachRemaining(allEvents::add);
        assertNotNull(allEvents);
        assertFalse(allEvents.isEmpty(), "Expected events");

        // getAllEvents consumes the source (streaming behavior)
        assertFalse(source.hasNext(), "Source should be exhausted after getAllEvents");
        assertEquals(NUM_EVENTS, allEvents.size());
    }

    @Test
    void resetRestartsIterationFromTheBeginning() {
        final PlatformContext context =
                PlatformContextFactory.createPlatformContext(Function.identity(), Function.identity());

        final PcesEventGraphSource source = new PcesEventGraphSource(pcesLocation, context);

        // Consume the source fully.
        final List<PlatformEvent> firstPass = new ArrayList<>();
        source.forEachRemaining(firstPass::add);
        assertEquals(NUM_EVENTS, firstPass.size());
        assertFalse(source.hasNext(), "Source should be exhausted before reset");

        // Reset and consume again.
        source.reset();
        assertTrue(source.hasNext(), "Source should have events again after reset");

        final List<PlatformEvent> secondPass = new ArrayList<>();
        source.forEachRemaining(secondPass::add);

        // The reset source must reproduce the same events in the same order. Raw PCES events are not hashed, so
        // compare the underlying gossip event rather than the (hash-dependent) descriptor.
        assertEquals(firstPass.size(), secondPass.size(), "Reset should reproduce the same number of events");
        for (int i = 0; i < firstPass.size(); i++) {
            assertEquals(
                    firstPass.get(i).getGossipEvent(),
                    secondPass.get(i).getGossipEvent(),
                    "Reset should reproduce the same events in the same order");
        }
    }

    @Test
    void emptyDirCreatesEmptySource() throws IOException {
        final PlatformContext context =
                PlatformContextFactory.createPlatformContext(Function.identity(), Function.identity());

        final Path empty = Files.createDirectory(baseDir.resolve("empty"));
        final PcesEventGraphSource source = new PcesEventGraphSource(empty, context);
        assertFalse(source.hasNext());
    }

    @Test
    void failsWhenCreatingAContextWithNonExistingDir() {
        final PlatformContext context =
                PlatformContextFactory.createPlatformContext(Function.identity(), Function.identity());

        Assertions.assertThrows(
                UncheckedIOException.class, () -> new PcesEventGraphSource(baseDir.resolve("non-existing"), context));
    }
}
