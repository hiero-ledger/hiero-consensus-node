// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.gui.hashgraph.internal.StandardGuiSource;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.test.fixtures.gui.HashgraphGuiRunner;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.FlowLayout;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.junit.jupiter.api.Test;

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

        final Path pcesDir = Path.of("/Users/kellygreco/Desktop/test_run/node0/migrated-pces/");
        final Roster roster = Roster.JSON.parse(
                new ReadableStreamingData(new FileInputStream(
                        "/Users/kellygreco/Desktop/test_run/node0/migrated-roster/new-roster.json")));
        // this will compact files in advance. the PcesFileReader will do the same thing and the these files will be
        // in the gradle cache and break the test. this seems to bypass that issue.
        PcesUtilities.compactPreconsensusEventFiles(pcesDir);

        final PcesFileTracker pcesFileTracker =
                PcesFileReader.readFilesFromDisk(context.getConfiguration(), context.getRecycleBin(), pcesDir, 0,
                        false);

        final TestIntake intake = new TestIntake(context, roster);
        final ConsensusOutput output = intake.getOutput();

        ConsensusRound latestRound = null;
        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(0, 0);

        long eventCount = 0;

        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            intake.addEvent(event);
            if (!output.getConsensusRounds().isEmpty()) {
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

        final StandardGuiSource guiSource = intake.createGuiSource();

        HashgraphGuiRunner.runHashgraphGui(guiSource, controls(
                intake.getConsensusEngine().getConsensus(),
                eventIterator,
                intake,
                guiSource));
    }

    public static @NonNull JPanel controls(
            final Consensus consensus,
            final PcesMultiFileIterator eventIterator,
            final TestIntake intake,
            final StandardGuiSource guiSource) {
        // Fame decided below
        final JLabel fameDecidedBelow = new JLabel("N/A");
        final Runnable updateFameDecidedBelow = () -> fameDecidedBelow.setText(
                "fame decided below: " + consensus.getFameDecidedBelow());
        updateFameDecidedBelow.run();
        // Next events
        final int defaultNumEvents = 1;
        final int numEventsMinimum = 1;
        final int numEventsStep = 1;
        final JSpinner numEvents = new JSpinner(new SpinnerNumberModel(
                Integer.valueOf(defaultNumEvents),
                Integer.valueOf(numEventsMinimum),
                Integer.valueOf(Integer.MAX_VALUE),
                Integer.valueOf(numEventsStep)));
        final JButton nextEvent = new JButton("Next events");
        nextEvent.addActionListener(e -> {
            try {
                final int numToAdd = numEvents.getValue() instanceof final Integer value ? value : defaultNumEvents;
                for (int i = 0; i < numToAdd; i++) {
                    if (eventIterator.hasNext()) {
                        final PlatformEvent event = eventIterator.next();
                        intake.addEvent(event);
                        guiSource.getEventStorage().updateMaxGen(event);
                    } else {
                        System.out.println("No more events");
                    }
                }
            } catch (final IOException ex) {
                throw new RuntimeException(ex);
            }
            updateFameDecidedBelow.run();
        });

        // create JPanel
        final JPanel controls = new JPanel(new FlowLayout());
        controls.add(nextEvent);
        controls.add(numEvents);
        controls.add(fameDecidedBelow);

        return controls;
    }
}
