// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.test.fixtures.PlatformTestUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.hiero.base.utility.test.fixtures.io.ResourceExtractor;
import org.hiero.consensus.gui.api.TestGuiSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.ConsensusOutput;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.TestIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation.RoundInternalEqualityValidation;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.io.NoOpRecycleBin;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.test.fixtures.PcesFileIteratorFactory;
import org.hiero.consensus.pcli.graph.PcesEventGraphSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for verifying the proper functioning of the minimum consensus relevant threshold in the consensus algorithm.
 */
public class MinConsensusRelevantThresholdTest {

    private static final String RESOURCE_DIR = "org/hiero/consensus/pcli/minConsensusRelevantThresholdTest/";
    private static final String NODE_0_DIR = "node0";
    private static final String NODE_3_DIR = "node3";
    private static final String PCES_DIR = "preconsensusEvents";
    private static final String ROSTER_FILE = "roster.json";

    private static final String ORIG_RESOURCE_DIR = "/Users/kellygreco/Desktop/ISS_HAPI_Test/build/hapi-test/hapiTestMiscRecordsSerial/node0/data/saved/";
    private static final String ORIG_PCES_DIR = "preconsensus-events";

    @TempDir
    Path testDataDirectory;

    @BeforeEach
    void setup() throws IOException {
        final ResourceExtractor<MinConsensusRelevantThresholdTest> loader =
                new ResourceExtractor<>(MinConsensusRelevantThresholdTest.class);
        final Path tempDir = loader.loadDirectory(RESOURCE_DIR);
        Files.move(tempDir, testDataDirectory, REPLACE_EXISTING);
    }


    @Test
    void runGuiWithPces() throws IOException, ParseException {
        final int initialEvents = 10;

        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final Metrics metrics = new NoOpMetrics();
        final RecycleBin recycleBin = new NoOpRecycleBin();

        final Path resourceDir = Path.of(ORIG_RESOURCE_DIR);
        final Path rosterPath = resourceDir.resolve(ROSTER_FILE);
        final Path pcesPath = resourceDir.resolve(ORIG_PCES_DIR);
        final Roster roster = Roster.JSON.parse(new ReadableStreamingData(new FileInputStream(rosterPath.toFile())));

        final PcesEventGraphSource source = new PcesEventGraphSource(pcesPath, configuration, recycleBin);

        final TestGuiSource guiSource =
                new TestGuiSource(metrics, configuration, roster, source);
        guiSource.generateEvents(initialEvents);
        guiSource.runGui();
    }


    /**
     * This test exercises a bug that has since been fixed that caused an ISS. Using an improper value for the minimum
     * consensus relevant threshold causes events to be assigned a voting round of
     * {@link org.hiero.consensus.model.hashgraph.ConsensusConstants#ROUND_NEGATIVE_INFINITY} when they should not be.
     * Specifically, this happened when the threshold was changed from nGen to sequence number. The PCES files used in
     * this test caused a divergence in round 3 when sequence number was used.
     *
     * @throws IOException    if there is a problem reading the resources from disk
     * @throws ParseException if there is a problem parsing the roster from the json file
     */
    @Test
    void testMinConsensusRelevantThreshold() throws IOException, ParseException {
        final Path rosterPath = testDataDirectory.resolve(ROSTER_FILE);
        final Roster roster = Roster.JSON.parse(new ReadableStreamingData(new FileInputStream(rosterPath.toFile())));
        final ConsensusOutput consensusOutput_node0 = getConsensusOutput(roster, testDataDirectory.resolve(NODE_0_DIR));
        final ConsensusOutput consensusOutput_node3 = getConsensusOutput(roster, testDataDirectory.resolve(NODE_3_DIR));
        assertEquals(
                consensusOutput_node0.getLatestRound(),
                consensusOutput_node3.getLatestRound(),
                "The PCES files for each node should results in the same number of rounds");
        for (int i = 0; i < consensusOutput_node0.getLatestRound(); i++) {
            RoundInternalEqualityValidation.INSTANCE.validate(
                    consensusOutput_node0.getConsensusRounds().get(i),
                    consensusOutput_node3.getConsensusRounds().get(i));
        }
    }

    /**
     * Reads the PCES and roster from the specified resource path and feeds the events into consensus.
     *
     * @param roster       the roster used to generate the PCES files
     * @param resourcePath the path where pces files for this test are located
     * @return the consensus output after replaying all events
     * @throws IOException if there is a problem reading the resources from disk
     */
    @NonNull
    private ConsensusOutput getConsensusOutput(@NonNull final Roster roster, @NonNull final Path resourcePath)
            throws IOException {
        final Path pcesPath = resourcePath.resolve(PCES_DIR);
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();

        final TestIntake intake = new TestIntake(configuration, roster);
        final IOIterator<PlatformEvent> eventIterator = PcesFileIteratorFactory.createIterator(pcesPath);
        try (eventIterator) {
            while (eventIterator.hasNext()) {
                final PlatformEvent event = eventIterator.next();
                intake.addEvent(event);
            }
        }
        return intake.getOutput();
    }
}
