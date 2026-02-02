// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static com.swirlds.platform.test.fixtures.PlatformTestUtils.createDefaultPlatformContext;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.test.fixtures.resource.ResourceLoader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.pces.impl.test.fixtures.PcesFileIteratorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the proper updating of {@link ConsensusRounds}'s maxRoundCreated variable.
 */
public class MaxRoundCreatedTest {

    private static final String RESOURCE_DIR = "com/swirlds/platform/consensus/maxRoundCreatedTest/";
    private static final String PCES_DIR = "preconsensusEvents";
    private static final String ROSTER_FILE = "roster.json";

    @TempDir
    Path testDataDirectory;

    @BeforeEach
    void setup() throws IOException {
        final ResourceLoader<MaxRoundCreatedTest> loader = new ResourceLoader<>(MaxRoundCreatedTest.class);
        final Path tempDir = loader.loadDirectory(RESOURCE_DIR);
        Files.move(tempDir, testDataDirectory, REPLACE_EXISTING);
    }

    /**
     * <p>
     * This test exercises a very specific scenario that previously caused consensus to get stuck.
     * </p>
     * <p>
     * A witness in voting round 3 was added (this witness collects votes on round 1 elections), but fame of one of the
     * witnesses in election round 1 (W1) was still undecided. Then, the last witness in the election round (W2) as
     * received by the consensus algorithm. W2 should have immediately been decided as not famous but it was not due to
     * a bug. The bug was that the maxRoundCreated variable was not always updated when it should have been. This
     * failure caused the logic that should have immediately decided the witness as not famous to be skipped. None of
     * the rest of the events with the allowed birth round were able to decide the fame of the witness, so consensus got
     * stuck.
     * </p>
     *
     * @throws IOException
     * @throws ParseException
     */
    @Test
    void testMaxRoundCreated() throws IOException, ParseException {
        final Path pcesDir = testDataDirectory.resolve(PCES_DIR);
        final Path rosterPath = testDataDirectory.resolve(ROSTER_FILE);
        final Roster roster = Roster.JSON.parse(new ReadableStreamingData(new FileInputStream(rosterPath.toFile())));

        final PlatformContext context = createDefaultPlatformContext();

        final TestIntake intake = new TestIntake(context, roster);
        final ConsensusOutput output = intake.getOutput();

        ConsensusRound latestRound = null;
        try (final IOIterator<PlatformEvent> eventIterator = PcesFileIteratorFactory.createIterator(pcesDir)) {
            while (eventIterator.hasNext()) {
                final PlatformEvent event = eventIterator.next();
                intake.addEvent(event);
                if (!output.getConsensusRounds().isEmpty()) {
                    latestRound = output.getConsensusRounds().getLast();
                }
                output.clear();
            }
            assertNotNull(latestRound, "Round 1 should have reached consensus, but no rounds reached consensus.");
            assertThat(latestRound.getRoundNum()).isEqualTo(1);
        }
    }
}
