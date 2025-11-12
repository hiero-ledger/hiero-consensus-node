// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.experiments;

import static org.hiero.otter.fixtures.KeysAndCertsConverter.fromProto;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.otter.fixtures.container.proto.ProtoKeysAndCerts;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A simple utility to extract a number of events from an event stream file.
 */
@Command(
        name = "event-signer",
        mixinStandardHelpOptions = true,
        version = "event-signer 1.0",
        description = "Extracts a number of events and signs them.")
public class Signer implements Callable<Integer> {

    @Option(
            names = {"-i", "--input"},
            description = "The file from which to extract the events.")
    private File inputFile;

    @Option(
            names = {"-o", "--output"},
            description = "The file in which the signed events will be stored.")
    private File outputFile;

    @Option(
            names = {"-c", "--certificates"},
            description = "The file in which the certificates for signing are stored.")
    private File certificatesFile;

    /**
     * Extracts events from the input file and write the signed events to the output file.
     *
     * @return exit code
     * @throws IOException if an I/O error occurs
     */
    @Override
    public Integer call() throws IOException, ParseException {
        final EventHasher eventHasher = new DefaultEventHasher();
        final Map<Long, PlatformSigner> signers = readSigners();

        final long startTime = System.nanoTime();

        try (final ReadableStreamingData inputStream = new ReadableStreamingData(inputFile.toPath());
                final WritableStreamingData outputStream =
                        new WritableStreamingData(new FileOutputStream(outputFile))) {
            while (inputStream.hasRemaining()) {
                final int len = inputStream.readInt();
                final Bytes eventBytes = inputStream.readBytes(len);
                final GossipEvent gossipEvent = GossipEvent.PROTOBUF.parse(eventBytes);
                final PlatformEvent platformEvent = new PlatformEvent(gossipEvent);
                eventHasher.hashEvent(platformEvent);
                final PlatformSigner signer =
                        signers.get(gossipEvent.eventCore().creatorNodeId());
                final Signature signature = signer.sign(platformEvent.getHash());
                final GossipEvent outputEvent = new GossipEvent.Builder()
                        .eventCore(gossipEvent.eventCore())
                        .signature(signature.getBytes())
                        .parents(gossipEvent.parents())
                        .transactions(gossipEvent.transactions())
                        .build();
                outputStream.writeInt(outputEvent.protobufSize());
                outputStream.writeBytes(GossipEvent.PROTOBUF.toBytes(outputEvent));
            }
        }

        final long endTime = System.nanoTime();
        final double durationMinutes = (endTime - startTime) / 1_000_000_000.0 / 60.0;
        System.out.printf("Signing completed in %.2f minutes%n", durationMinutes);

        return 0;
    }

    private Map<Long, PlatformSigner> readSigners() throws IOException {
        final Map<Long, PlatformSigner> signers = new HashMap<>();
        try (ReadableStreamingData stream = new ReadableStreamingData(certificatesFile.toPath())) {
            while (stream.hasRemaining()) {
                final long creatorNodeId = stream.readLong();
                final int len = stream.readInt();
                final Bytes bytes = stream.readBytes(len);
                final KeysAndCerts keysAndCerts = fromProto(ProtoKeysAndCerts.parseFrom(bytes.toByteArray()));
                final PlatformSigner signer = new PlatformSigner(keysAndCerts);
                signers.put(creatorNodeId, signer);
            }
        }
        return signers;
    }

    /**
     * The main entry point for the extractor utility.
     *
     * @param args the command line arguments
     */
    public static void main(@NonNull final String[] args) {
        final int exitCode = new CommandLine(new Signer()).execute(args);
        System.exit(exitCode);
    }
}
