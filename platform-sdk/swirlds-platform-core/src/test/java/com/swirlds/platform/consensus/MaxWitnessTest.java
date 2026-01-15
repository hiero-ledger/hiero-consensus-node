package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.test.fixtures.resource.ResourceLoader;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MaxWitnessTest extends PlatformTest {

    @Test
    void testMaxWitness() throws IOException, ParseException {
        final Path pcesDir = loadResourceDir().resolve("preconsensusEvents");
        final Path rosterPath = loadResourceDir().resolve("roster.json");
        final Roster roster = Roster.JSON.parse(new ReadableStreamingData(new FileInputStream(rosterPath.toFile())));


        final PlatformContext context = createDefaultPlatformContext();
        final PcesFileTracker pcesFileTracker =
                PcesFileReader.readFilesFromDisk(context.getConfiguration(), context.getRecycleBin(), pcesDir, 0,
                        false);

        final TestIntake intake = new TestIntake(context, roster);
        final ConsensusOutput output = intake.getOutput();

        ConsensusRound latestRound = null;
        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(0, 0);

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

    private Path loadResourceDir() throws IOException {
        final ResourceLoader<MaxWitnessTest> loader =
                new ResourceLoader<>(MaxWitnessTest.class);
        return loader.loadDirectory("com/swirlds/platform/consensus/maxWitnessTest/");
    }
}
