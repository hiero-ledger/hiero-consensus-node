// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;

import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.data.BlockStreamJumpStartConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks {@link WrappedRecordBlockHashMigration#execute} end-to-end against a
 * pre-generated wrapped-record-hashes file and synthetic jumpstart config properties.
 *
 * <h2>Generating the wrapped-record-hashes file</h2>
 * <p>The {@link WrappedHashesGenerator} inner class can be run directly to produce
 * a wrapped-record-hashes protobuf file.
 *
 * <h2>Running the benchmark</h2>
 * <pre>
 *   ./gradlew :app:jmh -PjmhInclude="WrappedRecordBlockHashMigrationBenchmark" \
 *       -PjmhArgs="-jvmArgsAppend -Djmh.wrappedHashesFile=/path/to/wrapped-record-hashes.pb"
 * </pre>
 *
 * <p>The wrapped-record-hashes file is large and thus intentionally not committed to the
 * repository. Generate it locally with the inner class above before running. Jumpstart data
 * is constructed directly as config properties (matching the production path) and does not
 * require an external file.
 */
@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@org.openjdk.jmh.annotations.State(Scope.Benchmark)
public class WrappedRecordBlockHashMigrationBenchmark {

    /** System property for the wrapped-record-hashes protobuf file path. */
    private static final String WRAPPED_HASHES_FILE_PROP = "jmh.wrappedHashesFile";

    /** Conventional default location (relative to the working directory). */
    private static final String DEFAULT_WRAPPED_HASHES_FILE = "wrapped-record-hashes.pb";

    private static final int HASH_COUNT = 30;
    private static final int HASH_SIZE = 48;

    // -------------------------------------------------------------------------
    // State set up once per trial
    // -------------------------------------------------------------------------

    private BlockRecordStreamConfig config;
    private BlockStreamJumpStartConfig jumpstartConfig;
    private Path wrappedRecordHashesDir;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        final String wrappedRecordHashesFilePath =
                System.getProperty(WRAPPED_HASHES_FILE_PROP, DEFAULT_WRAPPED_HASHES_FILE);

        // The migration resolves <wrappedRecordHashesDir>/wrapped-record-hashes.pb, so
        // copy the supplied file into a temp dir under that canonical name.
        wrappedRecordHashesDir = Files.createTempDirectory("jmh-wrapped-hashes-");
        Files.copy(
                Path.of(wrappedRecordHashesFilePath),
                wrappedRecordHashesDir.resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING);

        // Construct jumpstart config directly (matching the production config-property path)
        final var rng = new Random();
        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0L);
        var prevHash = new byte[HASH_SIZE];
        for (int i = 0; i < HASH_COUNT; i++) {
            final var randomHash = new byte[HASH_SIZE];
            rng.nextBytes(randomHash);
            hasher.addNodeByHash(randomHash);
            if (i == HASH_COUNT - 1) {
                prevHash = randomHash;
            }
        }
        final var subtreeHashes = hasher.intermediateHashingState();
        jumpstartConfig = new BlockStreamJumpStartConfig(
                0L, Bytes.wrap(prevHash), hasher.leafCount(), subtreeHashes.size(), subtreeHashes);

        config = new BlockRecordStreamConfig(
                "/tmp/logDir",
                "sidecar",
                2,
                5000,
                256,
                6,
                6,
                256,
                "concurrent",
                false,
                wrappedRecordHashesDir.toString(),
                true,
                false);
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        Files.deleteIfExists(wrappedRecordHashesDir.resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME));
        Files.deleteIfExists(wrappedRecordHashesDir);
    }

    // -------------------------------------------------------------------------
    // Benchmark
    // -------------------------------------------------------------------------

    @Benchmark
    public void execute() {
        new WrappedRecordBlockHashMigration().execute(StreamMode.BOTH, config, jumpstartConfig);
    }

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(
                new String[] {"com.hedera.node.app.records.impl.WrappedRecordBlockHashMigrationBenchmark"});
    }

    // =========================================================================
    // Input file generator
    // =========================================================================

    /**
     * Generates a wrapped-record-hashes protobuf file with a configurable number of entries.
     *
     * <p>The file format matches what {@link WrappedRecordFileBlockHashesDiskWriter} produces:
     * a sequence of length-delimited occurrences of {@code WrappedRecordFileBlockHashes}, each
     * containing a block number and two random 48-byte SHA-384 hashes.
     *
     * <p>Usage: {@code java WrappedHashesGenerator [output-path [entry-count]]}
     * Defaults: output = {@value #DEFAULT_OUT}, entries = {@value #DEFAULT_COUNT}
     */
    public static final class WrappedHashesGenerator {
        private static final String DEFAULT_OUT = "wrapped-record-hashes.pb";
        private static final int DEFAULT_COUNT = 500_000;

        static void main(String[] args) throws IOException {
            final Path out = Path.of(args.length > 0 ? args[0] : DEFAULT_OUT);
            final int count = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_COUNT;

            final var rng = new Random(42); // deterministic seed for reproducibility
            final var h1 = new byte[48];
            final var h2 = new byte[48];

            final long startMs = System.currentTimeMillis();
            try (var os = new BufferedOutputStream(new FileOutputStream(out.toFile()), 1 << 20)) {
                final var entryBuf = new ByteArrayOutputStream(128);
                for (int i = 0; i < count; i++) {
                    entryBuf.reset();
                    rng.nextBytes(h1);
                    rng.nextBytes(h2);

                    // Field 1: blockNumber (int64 varint) — omit for 0 (proto3 default)
                    if (i != 0) {
                        entryBuf.write(0x08); // tag: field 1, wire type 0
                        writeVarInt(entryBuf, i);
                    }
                    // Field 2: consensusTimestampHash (bytes, 48)
                    entryBuf.write(0x12);
                    entryBuf.write(48);
                    entryBuf.write(h1);
                    // Field 3: outputItemsTreeRootHash (bytes, 48)
                    entryBuf.write(0x1A);
                    entryBuf.write(48);
                    entryBuf.write(h2);

                    // Outer field 1 of WrappedRecordFileBlockHashesLog
                    final byte[] entry = entryBuf.toByteArray();
                    os.write(0x0A); // (1 << 3) | 2
                    writeVarInt(os, entry.length);
                    os.write(entry);

                    if ((i + 1) % 100_000 == 0) {
                        System.out.printf("  Written %,d / %,d entries...%n", i + 1, count);
                    }
                }
            }
            final long fileSize = Files.size(out);
            System.out.printf(
                    "Done! Wrote %,d entries to %s (%,.1f MB) in %,d ms%n",
                    count, out.toAbsolutePath(), fileSize / 1024.0 / 1024.0, System.currentTimeMillis() - startMs);
        }

        private static void writeVarInt(java.io.OutputStream out, long value) throws IOException {
            while ((value & ~0x7FL) != 0) {
                out.write((int) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int) value);
        }
    }
}
