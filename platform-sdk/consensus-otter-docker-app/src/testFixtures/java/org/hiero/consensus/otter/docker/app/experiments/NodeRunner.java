package org.hiero.consensus.otter.docker.app.experiments;

import static java.util.stream.Collectors.toMap;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A simple utility to extract a number of events from an event stream file.
 */
@Command(
        name = "node-runner",
        mixinStandardHelpOptions = true,
        version = "node-runner 1.0",
        description = "Runs a single Otter node in a network.")
public class NodeRunner implements Callable<Integer> {

    @Option(names = {"-f", "--file"}, description = "The file from which to read the IP addresses.", required = true)
    private Path ipAddressesPath;

    @Option(names = {"-n", "--count"}, description = "The number of nodes in the network.", defaultValue = "7")
    private int count;

    @Option(names = {"--id"}, description = "The NodeId of the node.", required = true)
    private long id;

    @Override
    public Integer call() throws Exception {
        // Calculate self node ID
        final NodeId selfId = NodeId.of(id);

        // Generate keys and certificates for all nodes
        final List<NodeId> nodeIds = IntStream.range(0, count).mapToObj(NodeId::of).toList();
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(nodeIds, null);
        final KeysAndCerts selfKeysAndCerts = keysAndCertsMap.get(selfId);

        // Create the roster
        final List<RosterEntry> rosterEntries = keysAndCertsMap.entrySet().stream()
                .map(this::createRosterEntry)
                .toList();
        final Roster roster = Roster.newBuilder().rosterEntries(rosterEntries).build();

        // Start the Otter node
        final OtterNode node = new OtterNode();
        node.start(selfId, selfKeysAndCerts, roster);

        return 0;
    }

    private RosterEntry createRosterEntry(@NonNull final Entry<NodeId, KeysAndCerts> nodeIdKeysAndCertsEntry) {
        final Map<NodeId, ServiceEndpoint> serviceEndpoints;
        try (final Stream<String> lines = Files.lines(ipAddressesPath)) {
            serviceEndpoints = lines
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.split(","))
                    .map(parts -> {
                        final NodeId nodeId = NodeId.of(Long.parseLong(parts[0]));
                        final String host = parts[1].trim();
                        final int port = Integer.parseInt(parts[2]);
                        final ServiceEndpoint serviceEndpoint = ServiceEndpoint.newBuilder()
                                .domainName(host)
                                .port(port)
                                .build();
                        return Map.entry(nodeId, serviceEndpoint);
                    })
                    .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

            final NodeId nodeId = nodeIdKeysAndCertsEntry.getKey();
            final KeysAndCerts keysAndCerts = nodeIdKeysAndCertsEntry.getValue();
            final ServiceEndpoint serviceEndpoint = serviceEndpoints.get(nodeId);
            return RosterEntry.newBuilder()
                    .nodeId(nodeId.id())
                    .gossipCaCertificate(Bytes.wrap(keysAndCerts.sigCert().getEncoded()))
                    .gossipEndpoint(serviceEndpoint)
                    .weight(1L)
                    .build();
        } catch (final IOException | CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The main entry point for the extractor utility.
     *
     * @param args the command line arguments
     */
    public static void main(@NonNull final String[] args) {
        new CommandLine(new NodeRunner()).execute(args);
    }
}
