// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.experiments;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * A simple utility to extract a number of events from an event stream file.
 */
@Command(
        name = "event-analyzer",
        mixinStandardHelpOptions = true,
        version = "event-analyzer 1.0",
        description = "Analyzes the events.")
public class DataAnalyzer implements Callable<Integer> {

    @Parameters(index = "0", description = "The file from which to read the events.")
    private File input;

    /**
     * Extracts events from the input file and writes them to the output file.
     *
     * @return exit code
     * @throws IOException if an I/O error occurs
     */
    @Override
    public Integer call() throws IOException, ParseException {
        final Set<Long> uniqueNodeIds = new HashSet<>();
        long count = 0;
        try (final ReadableStreamingData inputStream = new ReadableStreamingData(input.toPath())) {
            while (inputStream.hasRemaining()) {
                final int len = inputStream.readInt();
                final Bytes eventBytes = inputStream.readBytes(len);
                final GossipEvent event = GossipEvent.PROTOBUF.parse(eventBytes);
                final long creatorNodeId = event.eventCore().creatorNodeId();
                if (uniqueNodeIds.add(creatorNodeId)) {
                    System.out.println(
                            "Creator-Ids: " + uniqueNodeIds.stream().sorted().toList());
                }
                count++;
            }
        }
        System.out.println("Total Events: " + count);
        return 0;
    }

    /**
     * The main entry point for the extractor utility.
     *
     * @param args the command line arguments
     */
    public static void main(@NonNull final String[] args) {
        final int exitCode = new CommandLine(new DataAnalyzer()).execute(args);
        System.exit(exitCode);
    }
}
