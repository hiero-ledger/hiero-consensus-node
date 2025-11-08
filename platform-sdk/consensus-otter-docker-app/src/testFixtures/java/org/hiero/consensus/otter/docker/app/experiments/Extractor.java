package org.hiero.consensus.otter.docker.app.experiments;

import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A simple utility to extract a number of events from an event stream file.
 */
@Command(
        name = "extract-events",
        mixinStandardHelpOptions = true,
        version = "extract-events 1.0",
        description = "Extracts a number of events from an event stream file.")
public class Extractor implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, description = "The file from which to extract the events.")
    private File input;

    @Option(names = {"-o", "--output"}, description = "The file in which the events will be stored.")
    private File output;

    @Option(names = {"-n", "--count"}, description = "The number of events to extract.")
    private int count;

    /**
     * Extracts events from the input file and writes them to the output file.
     *
     * @return exit code
     * @throws IOException if an I/O error occurs
     */
    @Override
    public Integer call() throws IOException {
        int extracted = 0;
        try (final ReadableStreamingData inputStream = new ReadableStreamingData(input.toPath());
                final WritableStreamingData outputStream = new WritableStreamingData(new FileOutputStream(output))) {
            while (inputStream.hasRemaining() && extracted < count) {
                final int len = inputStream.readInt();
                outputStream.writeInt(len);
                outputStream.writeBytes(inputStream.readBytes(len));
                extracted++;
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
        final int exitCode = new CommandLine(new Extractor()).execute(args);
        System.exit(exitCode);
    }
}
