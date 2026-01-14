// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.crypto.PlatformSigner;
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

        final Path dir = Path.of("/Users/kellygreco/Desktop/test_run/node0/preconsensus-events/");
        final Roster roster = Roster.JSON.parse(
                new ReadableStreamingData(
                        new FileInputStream("/Users/kellygreco/Desktop/test_run/node0/currentRoster.json")));
        // this will compact files in advance. the PcesFileReader will do the same thing and the these files will be
        // in the gradle cache and break the test. this seems to bypass that issue.
        PcesUtilities.compactPreconsensusEventFiles(dir);

        final PcesFileTracker pcesFileTracker =
                PcesFileReader.readFilesFromDisk(context.getConfiguration(), context.getRecycleBin(), dir, 0, false);

        final Path consensusSnapshotPath = Path.of("/Users/kellygreco/Desktop/test_run/node0/consensusSnapshot.json");
        final ConsensusSnapshot consensusSnapshot =
                ConsensusSnapshot.JSON.parse(
                        new ReadableStreamingData(new FileInputStream(consensusSnapshotPath.toFile())));

        final TestIntake intake = new TestIntake(context, roster);
        intake.loadSnapshot(consensusSnapshot);

        final ConsensusOutput output = intake.getOutput();

        ConsensusRound latestRound = null;
        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(0, 0);

        final Map<Long, PlatformSigner> signers = generateSigners();
        // TODO write this to disk
        final Roster newRoster = generateRoster(signers);
        final List<PlatformEvent> migratedEvents = migrateEvents(eventIterator, signers);



        for (final PlatformEvent event : migratedEvents) {
            intake.addEvent(event);
            if (!output.getConsensusRounds().isEmpty()) {
                latestRound = output.getConsensusRounds().getLast();
            }
            output.clear();
        }
        System.out.println("Latest round: " + (latestRound != null ? latestRound.getRoundNum() : "none"));
    }

    private List<PlatformEvent> migrateEvents(@NonNull final PcesMultiFileIterator eventIterator,
            final Map<Long, PlatformSigner> signers) throws IOException {
        final List<PlatformEvent> migratedEvents = new ArrayList<>();
        final Map<Bytes, EventDescriptor> migratedParents = new HashMap<>();

        final PbjStreamHasher eventHasher = new PbjStreamHasher();
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            switch ((int) event.getCreatorId().id()) {
                case 0, 5: {
                    if (event.getNGen() < 23886) {
                        continue;
                    }
                    break;
                }
                case 1, 3: {
                    if (event.getNGen() < 23887) {
                        continue;
                    }
                    break;
                }
                case 2: {
                    if (event.getNGen() < 23888) {
                        continue;
                    }
                    break;
                }
                case 4: {
                    if (event.getNGen() < 23890) {
                        continue;
                    }
                    break;
                }
                case 6: {
                    if (event.getNGen() < 23892) {
                        continue;
                    }
                    break;
                }
                default: {
                    System.err.println("Unknown event creator: " + event.getCreatorId());
                }
            }
            // Calculate the old hash
            eventHasher.hashEvent(event);

            // Create the new event core
            final EventCore oldEventCore = event.getEventCore();
            final EventCore newEventCore = oldEventCore.copyBuilder()
                    .birthRound(oldEventCore.birthRound() - 79680)
                    .build();

            // Get the updated parent descriptors
            final List<EventDescriptor> parents = event.getAllParents().stream()
                    .map(parent -> migratedParents.get(parent.hash()))
                    .filter(Objects::nonNull)
                    .toList();

            // Calculate the new hash (clear any transactions)
            final Hash newHash = eventHasher.hashEvent(newEventCore, parents, Collections.emptyList());

            // sign new hash
            final PlatformSigner signer = signers.get(newEventCore.creatorNodeId());
            final Signature signature = signer.sign(newHash.getBytes().toByteArray());

            // Build the migrated GossipEvent
            final GossipEvent migratedGossipEvent = new GossipEvent.Builder()
                    .eventCore(newEventCore)
                    .signature(signature.getBytes())
                    .parents(parents)
                    .build();

            //  Create the new event descriptor for the migrated event
            final EventDescriptor eventDescriptor = new EventDescriptor.Builder()
                    .hash(newHash.getBytes())
                    .creatorNodeId(newEventCore.creatorNodeId())
                    .birthRound(newEventCore.birthRound())
                    .build();

            // Store the migrated event descriptor for future events to lookup as parents
            migratedParents.put(newHash.getBytes(), eventDescriptor);

            // Add to the list of migrated events to return.
            migratedEvents.add(new PlatformEvent(migratedGossipEvent));
        }
        return migratedEvents;
    }

    /**
     * Create a Roster for the given signers
     *
     * @return
     */
    private Roster generateRoster(final Map<Long, PlatformSigner> signers) {
        // TODO
        return null;
    }

    /**
     * Creates platform signers for 7 nodes
     *
     * @return
     */
    private Map<Long, PlatformSigner> generateSigners() {
        // TODO
        return null;
    }
}
