// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.EventCore;
import com.hedera.hapi.platform.event.EventDescriptor;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.event.orphan.DefaultOrphanBuffer;
import com.swirlds.platform.event.preconsensus.CommonPcesWriter;
import com.swirlds.platform.event.preconsensus.PcesFileManager;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class PcesGraphSlicer extends PlatformTest {
    public static final Path HOME = Paths.get(System.getProperty("user.home"));
    public static final Path SNAPSHOT_PATH = HOME.resolve(Path.of("Desktop/test_run/node0/consensusSnapshot.json"));
    public static final Path PCES_LOCATION = HOME.resolve(Path.of("Desktop/test_run/node0/preconsensus-events/"));
    public static final Path PCES_OUTPUT_LOCATION = HOME.resolve(Path.of("Desktop/test_run/node0/migrated-pces"));
    public static final Path ROSTER_OUTPUT_LOCATION =
            HOME.resolve(Path.of("Desktop/test_run/node0/migrated-roster/new-roster.json"));
    private static final Path ROSTER_LOCATION = HOME.resolve(Path.of("Desktop/test_run/node0/currentRoster.json"));


    private final PbjStreamHasher eventHasher = new PbjStreamHasher();

    /**
     * A test that reads in a set of PCES event files and checks that the coin round occurred. The test expects the
     * following directory structure:
     * <ol>
     *     <li>supplied-dir/config.txt</li>
     *     <li>supplied-dir/events/*.pces</li>
     * </ol>
     */
    @Test
    void sliceGraph() throws IOException, ParseException, KeyStoreException, ExecutionException, InterruptedException {
        final PlatformContext context = createDefaultPlatformContext();

        final List<NodeId> nodeIds = IntStream.range(0, 7).mapToObj(NodeId::of).toList();
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(nodeIds, null);
        final Map<NodeId, PlatformSigner> signers = generateSigners(keysAndCertsMap);
        final Roster roster = generateRoster(keysAndCertsMap);
        writeRoster(roster);

        // this will compact files in advance. the PcesFileReader will do the same thing and the these files will be
        // in the gradle cache and break the test. this seems to bypass that issue.
        PcesUtilities.compactPreconsensusEventFiles(PCES_LOCATION);

        final PcesFileTracker pcesFileTracker = PcesFileReader.readFilesFromDisk(
                context.getConfiguration(), context.getRecycleBin(), PCES_LOCATION, 0, false);

        final ConsensusSnapshot consensusSnapshot =
                ConsensusSnapshot.JSON.parse(new ReadableStreamingData(new FileInputStream(SNAPSHOT_PATH.toFile())));

        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(0, 0);

        final DefaultOrphanBuffer orphanBuffer = new DefaultOrphanBuffer(context.getMetrics(),
                new NoOpIntakeEventCounter());
        orphanBuffer.setEventWindow(EventWindowUtils.createEventWindow(consensusSnapshot, 26));
        final List<PlatformEvent> nonOrphans = new ArrayList<>();

        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            eventHasher.hashEvent(event);
            final var events = orphanBuffer.handleEvent(event);
            nonOrphans.addAll(events);
        }

        final List<PlatformEvent> migratedEvents = migrateEvents(nonOrphans, signers);
        writeEvents(context, migratedEvents);
    }

    private void writeRoster(final Roster newRoster) {
        try {
            Files.createDirectories(ROSTER_OUTPUT_LOCATION.getParent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try (final BufferedWriter writer = new BufferedWriter(new FileWriter(ROSTER_OUTPUT_LOCATION.toFile()))) {
            writer.write(Roster.JSON.toJSON(newRoster));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<PlatformEvent> migrateEvents(@NonNull final List<PlatformEvent> events,
            final Map<NodeId, PlatformSigner> signers) {
        final Map<Bytes, EventDescriptor> migratedParents = new HashMap<>();
        final List<PlatformEvent> migratedEvents = new ArrayList<>();
        for (final PlatformEvent event : events) {
            switch ((int) event.getCreatorId().id()) {
                case 0, 5: {
                    if (event.getNGen() == 23886) {
                        System.out.println(event.getEventCore().creatorNodeId() + " " + event.getBirthRound());
                    }
                    if (event.getNGen() < 23886) {
                        continue;
                    }
                    break;
                }
                case 1, 3: {
                    if (event.getNGen() == 23887) {
                        System.out.println(event.getEventCore().creatorNodeId() + " " + event.getBirthRound());
                    }
                    if (event.getNGen() < 23887) {
                        continue;
                    }
                    break;
                }
                case 2: {
                    if (event.getNGen() == 23888) {
                        System.out.println(event.getEventCore().creatorNodeId() + " " + event.getBirthRound());
                    }
                    if (event.getNGen() < 23888) {
                        continue;
                    }
                    break;
                }
                case 4: {
                    if (event.getNGen() == 23890) {
                        System.out.println(event.getEventCore().creatorNodeId() + " " + event.getBirthRound());
                    }
                    if (event.getNGen() < 23890) {
                        continue;
                    }
                    break;
                }
                case 6: {
                    if (event.getNGen() == 23892) {
                        System.out.println(event.getEventCore().creatorNodeId() + " " + event.getBirthRound());
                    }
                    if (event.getNGen() < 23892) {
                        continue;
                    }
                    break;
                }
                default: {
                    System.err.println("Unknown event creator: " + event.getCreatorId());
                    return null;
                }
            }
            // Create the new event core
            final EventCore oldEventCore = event.getEventCore();
            final EventCore newEventCore = oldEventCore
                    .copyBuilder()
                    .birthRound(Long.max(oldEventCore.birthRound() - 79679, 1))
                    .build();

            // Get the updated parent descriptors
            final List<EventDescriptor> parents = event.getAllParents().stream()
                    .map(parent -> migratedParents.get(parent.hash().getBytes()))
                    .filter(Objects::nonNull)
                    .toList();

            // Calculate the new hash (clear any transactions)
            final Hash newHash = eventHasher.hashEvent(newEventCore, parents, Collections.emptyList());

            // sign new hash
            final PlatformSigner signer = signers.get(event.getCreatorId());
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
            migratedParents.put(event.getHash().getBytes(), eventDescriptor);

            // Add the migrated event to the list to return
            migratedEvents.add(new PlatformEvent(migratedGossipEvent));
        }
        return migratedEvents;
    }

    private void writeEvents(final PlatformContext platformContext, final List<PlatformEvent> migratedEvents) {
        try {
            Files.createDirectories(PCES_OUTPUT_LOCATION);
            final CommonPcesWriter pcesWriter = new CommonPcesWriter(
                    platformContext,
                    new PcesFileManager(platformContext, new PcesFileTracker(), PCES_OUTPUT_LOCATION, 0));

            pcesWriter.beginStreamingNewEvents();
            for (PlatformEvent event : migratedEvents) {
                pcesWriter.prepareOutputStream(event);
                pcesWriter.getCurrentMutableFile().writeEvent(event);
            }
            pcesWriter.closeCurrentMutableFile();
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    /**
     * Creates platform signers for 7 nodes
     *
     * @return
     */
    private Map<NodeId, PlatformSigner> generateSigners(final Map<NodeId, KeysAndCerts> keysAndCertsMap) {
        final Map<NodeId, PlatformSigner> signers = new HashMap<>();
        keysAndCertsMap.forEach((nodeId, keysAndCerts) -> signers.put(nodeId, new PlatformSigner(keysAndCerts)));
        return signers;
    }

    /**
     * Create a Roster for the given signers
     *
     * @return
     */
    private Roster generateRoster(@NonNull final Map<NodeId, KeysAndCerts> signers) {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        for (final Entry<NodeId, KeysAndCerts> signer : signers.entrySet()) {
            rosterEntries.add(createRosterEntry(signer.getKey(), signer.getValue()));
        }
        rosterEntries.sort(Comparator.comparingLong(RosterEntry::nodeId));
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    private RosterEntry createRosterEntry(final NodeId nodeId, final KeysAndCerts keysAndCerts) {
        try {
            final long id = nodeId.id();
            final byte[] certificate = keysAndCerts.sigCert().getEncoded();
            return RosterEntry.newBuilder()
                    .nodeId(id)
                    .weight(500)
                    .gossipCaCertificate(Bytes.wrap(certificate))
                    .gossipEndpoint(ServiceEndpoint.newBuilder()
                            .domainName(String.format("node-%d", id))
                            .port(8082)
                            .build())
                    .build();
        } catch (final CertificateEncodingException e) {
            throw new RuntimeException("Exception while creating roster entry", e);
        }
    }
}
