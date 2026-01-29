// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import static com.swirlds.platform.test.fixtures.PlatformTestUtils.generateRoster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.test.fixtures.PlatformTestUtils;
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
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(NODE_IDS);
        final Roster roster = generateRoster(keysAndCertsMap);
        TestEventUtils.generatePreConsensusStream(context, pcesLocation, roster, keysAndCertsMap, NUM_EVENTS);
    }

    @Test
    void sourceHasNextAndNextWorks() {
        final PlatformContext context =
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());

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
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());

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
    void emptyDirCreatesEmptySource() throws IOException {
        final PlatformContext context =
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());

        final Path empty = Files.createDirectory(baseDir.resolve("empty"));
        final PcesEventGraphSource source = new PcesEventGraphSource(empty, context);
        assertFalse(source.hasNext());
    }

    @Test
    void failsWhenCreatingAContextWithNonExistingDir() {
        final PlatformContext context =
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());

        Assertions.assertThrows(
                UncheckedIOException.class, () -> new PcesEventGraphSource(baseDir.resolve("non-existing"), context));
    }
}
