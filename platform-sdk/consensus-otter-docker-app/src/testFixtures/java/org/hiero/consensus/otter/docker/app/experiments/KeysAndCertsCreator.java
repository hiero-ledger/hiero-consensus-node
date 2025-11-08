package org.hiero.consensus.otter.docker.app.experiments;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStoreException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.KeysAndCertsConverter;
import org.hiero.otter.fixtures.container.proto.ProtoKeysAndCerts;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A simple utility to extract a number of events from an event stream file.
 */
@Command(
        name = "keys-and-certs-generator",
        mixinStandardHelpOptions = true,
        version = "keys-and-certs-generator 1.0",
        description = "Creates a KeysAndCerts-object for each creator found in the event stream.")
public class KeysAndCertsCreator implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, description = "The file from which to extract the events.")
    private File inputFile;

    @Option(names = {"-c", "--certificates"}, description = "The file in which the certificates will be stored.")
    private File certificatesFile;

    /**
     * Extracts events from the input file and writes them to the output file.
     *
     * @return exit code
     * @throws IOException if an I/O error occurs
     */
    @Override
    public Integer call()
            throws IOException, ParseException, KeyStoreException, ExecutionException, InterruptedException {
        final Set<Long> uniqueNodeIds = new HashSet<>();
        try (final ReadableStreamingData inputStream = new ReadableStreamingData(inputFile.toPath())) {
            while (inputStream.hasRemaining()) {
                final int len = inputStream.readInt();
                final Bytes eventBytes = inputStream.readBytes(len);
                final GossipEvent event = GossipEvent.PROTOBUF.parse(eventBytes);
                final long creatorNodeId = event.eventCore().creatorNodeId();
                uniqueNodeIds.add(creatorNodeId);
            }
        }

        final List<NodeId> nodeIds = uniqueNodeIds.stream().sorted().map(NodeId::of).toList();
        final Map<NodeId, KeysAndCerts> keysAndCertsMap = CryptoStatic.generateKeysAndCerts(nodeIds, null);

        try (final WritableStreamingData outputStream = new WritableStreamingData(new FileOutputStream(certificatesFile))) {
            for (final NodeId nodeId : nodeIds) {
                final KeysAndCerts keysAndCerts = keysAndCertsMap.get(nodeId);
                outputStream.writeLong(nodeId.id());
                final ProtoKeysAndCerts protoKeysAndCerts = KeysAndCertsConverter.toProto(keysAndCerts);
                outputStream.writeInt(protoKeysAndCerts.getSerializedSize());
                outputStream.writeBytes(protoKeysAndCerts.toByteArray());
            }
        }

        return 0;
    }

    /**
     * The main entry point for the extractor utility.
     *
     * @param args the command line arguments
     */
    public static void main(@NonNull final String[] args) {
        final int exitCode = new CommandLine(new KeysAndCertsCreator()).execute(args);
        System.exit(exitCode);
    }
}
