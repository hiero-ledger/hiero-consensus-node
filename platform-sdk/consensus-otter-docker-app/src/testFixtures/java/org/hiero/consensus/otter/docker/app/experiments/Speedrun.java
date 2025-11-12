// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.experiments;

import static org.hiero.otter.fixtures.KeysAndCertsConverter.fromProto;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.platform.TimestampCollector;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.container.proto.ProtoKeysAndCerts;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A simple utility to extract a number of events from an event stream file.
 */
@Command(
        name = "rate-analyzer",
        mixinStandardHelpOptions = true,
        version = "rate-analyzer 1.0",
        description = "Determines the rate with which we can feed the pipeline.")
public class Speedrun implements Callable<Integer> {

    private static final NodeId SELF_ID = NodeId.of(100L);

    @Option(
            names = {"-i", "--input"},
            description = "The file from which to extract the events.")
    private File inputFile;

    @Option(
            names = {"-c", "--certificates"},
            description = "The file in which the certificates for signing are stored.")
    private Path certificatesPath;

    @Option(
            names = {"-r", "--rate"},
            description = "The number of events that are submitted per second.")
    private int rate = 500;

    /**
     * Extracts events from the input file and write the signed events to the output file.
     *
     * @return exit code
     * @throws IOException if an I/O error occurs
     */
    @Override
    public Integer call() throws Exception {

        // Generate keys and certificates for the node
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(List.of(SELF_ID), null);
        final KeysAndCerts selfKeysAndCerts = keysAndCertsMap.get(SELF_ID);

        // Create the roster
        final Roster roster = createRoster(selfKeysAndCerts);

        // Start the Otter node
        final OtterNode node = new OtterNode();
        node.start(SELF_ID, selfKeysAndCerts, roster);

        // Read the events
        final GossipEvent[] events = readEvents().toArray(new GossipEvent[0]);

        // Initialize the round timer
        final long startTime = System.nanoTime();
        final long nanosPerEvent = 1_000_000_000L / rate;

        for (int i = 0, n = events.length; i < n; i++) {
            node.processEvent(events[i]);

            final long expectedTime = startTime + (i + 1) * nanosPerEvent;
            while (System.nanoTime() < expectedTime) {
                Thread.onSpinWait();
            }
        }

        System.out.println("Waiting 5 sec. for processing to finish");
        Thread.sleep(5000);

        TimestampCollector.store();

        return 0;
    }

    private Roster createRoster(@NonNull final KeysAndCerts selfKeysAndCerts)
            throws IOException, CertificateEncodingException {
        final List<RosterEntry> rosterEntries = new ArrayList<>();
        try (final ReadableStreamingData stream = new ReadableStreamingData(certificatesPath)) {
            while (stream.hasRemaining()) {
                final long creatorNodeId = stream.readLong();
                final int len = stream.readInt();
                final Bytes bytes = stream.readBytes(len);
                final KeysAndCerts keysAndCerts = fromProto(ProtoKeysAndCerts.parseFrom(bytes.toByteArray()));
                final Bytes certificate = Bytes.wrap(keysAndCerts.sigCert().getEncoded());
                final ServiceEndpoint serviceEndpoint = ServiceEndpoint.newBuilder()
                        .domainName("localhost")
                        .port(5005 + (int) creatorNodeId)
                        .build();
                final RosterEntry rosterEntry = RosterEntry.newBuilder()
                        .nodeId(creatorNodeId)
                        .gossipCaCertificate(certificate)
                        .gossipEndpoint(serviceEndpoint)
                        .weight(100L)
                        .build();
                rosterEntries.add(rosterEntry);
            }
        }
        final RosterEntry selfEntry = RosterEntry.newBuilder()
                .nodeId(SELF_ID.id())
                .gossipCaCertificate(Bytes.wrap(selfKeysAndCerts.sigCert().getEncoded()))
                .gossipEndpoint(ServiceEndpoint.newBuilder()
                        .domainName("localhost")
                        .port(5005 + (int) SELF_ID.id())
                        .build())
                .weight(100L)
                .build();
        rosterEntries.add(selfEntry);
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }

    private List<GossipEvent> readEvents() throws IOException, ParseException {
        final List<GossipEvent> events = new ArrayList<>();
        try (final ReadableStreamingData inputStream = new ReadableStreamingData(inputFile.toPath())) {
            while (inputStream.hasRemaining()) {
                final int len = inputStream.readInt();
                final Bytes eventBytes = inputStream.readBytes(len);
                final GossipEvent event = GossipEvent.PROTOBUF.parse(eventBytes);
                events.add(event);
            }
        }
        return events;
    }

    /**
     * The main entry point for the extractor utility.
     *
     * @param args the command line arguments
     */
    public static void main(@NonNull final String[] args) {
        final int exitCode = new CommandLine(new Speedrun()).execute(args);
        System.exit(exitCode);
    }
}
