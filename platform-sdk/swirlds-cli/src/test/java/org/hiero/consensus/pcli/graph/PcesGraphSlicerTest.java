// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventCore;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.test.fixtures.PlatformTestUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pcli.graph.utils.TestEventUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link PcesGraphSlicer}.
 */
public class PcesGraphSlicerTest {

    private static final int START_NODE_ID = 0;
    private static final int END_NODE_ID = 7;
    private static final List<NodeId> NODE_IDS =
            IntStream.range(START_NODE_ID, END_NODE_ID).mapToObj(NodeId::of).toList();
    private static final Long MINIMUM_BIRTHROUND = 60L;
    private static final int MIN_NGEN = 100;

    @TempDir
    private Path baseDir;

    private Path pcesLocation;
    private Path pcesOutputLocation;

    @BeforeEach
    void generatePces() throws IOException, KeyStoreException, ExecutionException, InterruptedException {
        pcesLocation = baseDir.resolve(Path.of("preconsensus-events"));
        pcesOutputLocation = baseDir.resolve(Path.of("migrated/preconsensus-events/"));
        FileUtils.deleteDirectory(pcesOutputLocation);
        final PlatformContext context =
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(NODE_IDS);
        final Roster roster = PlatformTestUtils.generateRoster(keysAndCertsMap);
        TestEventUtils.generatePreConsensusStream(context, pcesLocation, roster, keysAndCertsMap, 5000);
    }

    @ParameterizedTest
    @MethodSource("filters")
    public void sliceByPropertyTest(@NonNull final EventGraphPipeline.EventFilter filter)
            throws IOException, KeyStoreException, ExecutionException, InterruptedException {
        final PlatformContext newContext =
                PlatformTestUtils.createPlatformContext(Function.identity(), Function.identity());

        final Map<NodeId, KeysAndCerts> newKeysAndCertsMap = CryptoStatic.generateKeysAndCerts(NODE_IDS);
        final PcesGraphSlicer slicer = PcesGraphSlicer.builder()
                .context(newContext)
                .keysAndCertsMap(newKeysAndCertsMap)
                .graphEventFilter(filter)
                .graphEventCoreModifier(PcesGraphSlicerTest::overrideBirthround)
                .existingPcesFilesLocation(pcesLocation)
                .exportPcesFileLocation(pcesOutputLocation)
                .build();

        Assertions.assertDoesNotThrow(slicer::slice);
        assertTrue(Files.exists(pcesOutputLocation));
        assertFilteredPcesExist(pcesOutputLocation);

        final PcesEventGraphSource source = new PcesEventGraphSource(pcesLocation, newContext);
        final List<PlatformEvent> oldEventsInPCes = new ArrayList<>();
        source.forEachRemaining(oldEventsInPCes::add);
        final PcesEventGraphSource newSource = new PcesEventGraphSource(pcesOutputLocation, newContext);
        final List<PlatformEvent> newEventsInPCes = new ArrayList<>();
        newSource.forEachRemaining(newEventsInPCes::add);
        Assertions.assertTrue(
                newEventsInPCes.size() < oldEventsInPCes.size(),
                "Sliced PCES should have fewer events than the original, but got " + newEventsInPCes.size() + " vs "
                        + oldEventsInPCes.size());

        assertEitherGenesysEventsOrChildren(newEventsInPCes);
    }

    private static void assertEitherGenesysEventsOrChildren(@NonNull final List<PlatformEvent> newEventsInPCes) {
        final Map<NodeId, PlatformEvent> genesisEventPerCreator = new LinkedHashMap<>();
        for (PlatformEvent e : newEventsInPCes) {
            if (e.getAllParents().isEmpty() && !genesisEventPerCreator.containsKey(e.getCreatorId())) {
                genesisEventPerCreator.put(e.getCreatorId(), e);
            } else if (e.getAllParents().isEmpty()) {
                // We already have a genesys event for this creator, so we fail
                fail("more than 1 creator got a genesis event: " + e.getCreatorId());
            }
        }
    }

    private static void assertFilteredPcesExist(@NonNull final Path pcesFolder) throws IOException {
        try (final Stream<Path> walk = Files.walk(pcesFolder)) {
            final Optional<Path> pcesFile = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".pces"))
                    .filter(p -> {
                        try {
                            return Files.size(p) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst();
            assertTrue(pcesFile.isPresent(), "Expected at least one non-empty .pces file");
        }
    }

    private static List<EventGraphPipeline.EventFilter> filters() {
        return List.of(event -> event.getBirthRound() >= MINIMUM_BIRTHROUND, event -> event.getNGen() >= MIN_NGEN);
    }

    private static EventCore overrideBirthround(@NonNull final EventCore event) {
        return event.copyBuilder()
                .birthRound(Long.max(event.birthRound() - MINIMUM_BIRTHROUND, 1))
                .build();
    }
}
