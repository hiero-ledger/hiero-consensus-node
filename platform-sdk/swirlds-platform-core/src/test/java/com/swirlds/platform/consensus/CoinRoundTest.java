// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.test.fixtures.gui.HashgraphGuiRunner;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.roster.RosterRetriever;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class CoinRoundTest extends PlatformTest {

    /**
     * A test that reads in a set of PCES event files and checks that the coin round occurred. The test expects the
     * following directory structure:
     * <ol>
     *     <li>supplied-dir/config.txt</li>
     *     <li>supplied-dir/events/*.pces</li>
     * </ol>
     */
    @Test
    void coinRound() throws IOException, ParseException {
        final PlatformContext context = createDefaultPlatformContext();

        final Path dir = Path.of("/Users/lazarpetrovic/Downloads/pces3");
        final Roster roster = Roster.JSON.parse(
                new ReadableStreamingData(new FileInputStream("/Users/lazarpetrovic/Downloads/currentRoster.json")));
        // this will compact files in advance. the PcesFileReader will do the same thing and the these files will be
        // in the gradle cache and break the test. this seems to bypass that issue.
        PcesUtilities.compactPreconsensusEventFiles(dir);

        final PcesFileTracker pcesFileTracker =
                PcesFileReader.readFilesFromDisk(context.getConfiguration(), context.getRecycleBin(), dir, 0, false);

        final Path consensusSnapshotPath = Path.of("/Users/lazarpetrovic/Downloads/consensusSnapshot.json");
        final ConsensusSnapshot consensusSnapshot =
                ConsensusSnapshot.JSON.parse(new ReadableStreamingData(new FileInputStream(consensusSnapshotPath.toFile())));

        final TestIntake intake = new TestIntake(context, roster);
        intake.loadSnapshot(consensusSnapshot);

        final ConsensusOutput output = intake.getOutput();

        ConsensusRound latestRound = null;
        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(
                consensusSnapshot.minimumJudgeInfoList().getFirst().minimumJudgeBirthRound(),
                consensusSnapshot.round()
        );

        long eventCount = 0;

        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            if(event.getBirthRound() == 79681){
                continue;
            }
            intake.addEvent(event);
            if(!output.getConsensusRounds().isEmpty()){
                latestRound = output.getConsensusRounds().getLast();
            }
            output.clear();

            eventCount++;

            // Print memory stats every 1000 events
            if (eventCount % 1000 == 0) {
                System.out.printf("Events: %d, Round: %s%n",
                        eventCount, latestRound != null ? latestRound.getRoundNum() : "none");
            }
        }
        System.out.println("Latest round: " + (latestRound != null ? latestRound.getRoundNum() : "none"));
        System.out.println("Total events processed: " + eventCount);

        HashgraphGuiRunner.runHashgraphGui(intake.createGuiSource(),  null);
    }
}
