// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashesLog;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * pre-generated wrapped-record-hashes file and companion jumpstart file.
 *
 * <h2>Generating the input files</h2>
 * <p>Both generators are inner classes of this file and can be run directly
 * <ul>
 *   <li>{@link JumpstartGenerator} — produces a {@code jumpstart.bin} file</li>
 *   <li>{@link WrappedHashesGenerator} — produces a wrapped-record-hashes protobuf file</li>
 * </ul>
 *
 * <h2>Running the benchmark</h2>
 * <pre>
 *   ./gradlew :app:jmh -PjmhInclude="WrappedRecordBlockHashMigrationBenchmark" \
 *       -PjmhArgs="-jvmArgsAppend -Djmh.jumpstartFile=/path/to/jumpstart.bin \
 *                  -Djmh.wrappedHashesFile=/path/to/wrapped-record-hashes.pb"
 * </pre>
 *
 * <p>The data files are large and thus intentionally not committed to the repository. Generate
 * them locally with the inner classes above before running.
 */
@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@org.openjdk.jmh.annotations.State(Scope.Benchmark)
public class WrappedRecordBlockHashMigrationBenchmark {

    /** System property for the jumpstart binary file path. */
    private static final String JUMPSTART_FILE_PROP = "jmh.jumpstartFile";
    /** System property for the wrapped-record-hashes protobuf file path. */
    private static final String WRAPPED_HASHES_FILE_PROP = "jmh.wrappedHashesFile";

    /** Conventional default locations (relative to the working directory). */
    private static final String DEFAULT_JUMPSTART_FILE = "jumpstart.bin";

    private static final String DEFAULT_WRAPPED_HASHES_FILE = "wrapped-record-hashes.pb";

    // -------------------------------------------------------------------------
    // State set up once per trial
    // -------------------------------------------------------------------------

    private BlockRecordStreamConfig config;
    private FakeState state;
    private Path wrappedRecordHashesDir;
    private long lastBlockNumber;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        final String jumpstartFilePath = System.getProperty(JUMPSTART_FILE_PROP, DEFAULT_JUMPSTART_FILE);
        final String wrappedRecordHashesFilePath =
                System.getProperty(WRAPPED_HASHES_FILE_PROP, DEFAULT_WRAPPED_HASHES_FILE);

        // The migration resolves <wrappedRecordHashesDir>/wrapped-record-hashes.pb, so
        // copy the supplied file into a temp dir under that canonical name.
        wrappedRecordHashesDir = Files.createTempDirectory("jmh-wrapped-hashes-");
        Files.copy(
                Path.of(wrappedRecordHashesFilePath),
                wrappedRecordHashesDir.resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING);

        lastBlockNumber = readLastBlockNumber(
                wrappedRecordHashesDir.resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME));

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
                jumpstartFilePath);

        state = buildFakeState(lastBlockNumber);
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        Files.deleteIfExists(wrappedRecordHashesDir.resolve(WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME));
        Files.deleteIfExists(wrappedRecordHashesDir);
    }

    /**
     * Re-seed the fake state before each iteration so that the migration always sees
     * a fresh {@link BlockInfo} with the expected {@code lastBlockNumber}.
     * (The migration writes to state via {@code put()} and {@code commit()};
     * resetting the {@link AtomicReference} restores the original value.)
     */
    @Setup(Level.Iteration)
    public void resetState() {
        state = buildFakeState(lastBlockNumber);
    }

    // -------------------------------------------------------------------------
    // Benchmark
    // -------------------------------------------------------------------------

    @Benchmark
    public void execute() {
        new WrappedRecordBlockHashMigration().execute(StreamMode.BOTH, config, state);
    }

    public static void main(String... args) throws Exception {
        org.openjdk.jmh.Main.main(
                new String[] {"com.hedera.node.app.records.impl.WrappedRecordBlockHashMigrationBenchmark"});
    }

    private static long readLastBlockNumber(@NonNull final Path file) {
        try {
            final byte[] bytes = Files.readAllBytes(file);
            final var log = WrappedRecordFileBlockHashesLog.PROTOBUF.parse(
                    Bytes.wrap(bytes).toReadableSequentialData(), false, false, 512, bytes.length);
            return log.entries().getLast().blockNumber();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read last block number from " + file, e);
        }
    }

    private static FakeState buildFakeState(final long lastBlockNumber) {
        final var blockInfo =
                BlockInfo.newBuilder().lastBlockNumber(lastBlockNumber).build();
        final var blockInfoRef = new AtomicReference<>(blockInfo);
        return new FakeState().addService(BlockRecordService.NAME, Map.of(BLOCKS_STATE_ID, blockInfoRef));
    }

    // =========================================================================
    // Input file generators
    // =========================================================================

    /**
     * Generates a {@code jumpstart.bin} file suitable for use with this benchmark.
     *
     * <p>Replicates the logic of {@code BuildUpgradeZipOp.main2()}: seeds an
     * {@link IncrementalStreamingHasher} with 30 random 48-byte hashes, then writes
     * the binary jumpstart format:
     * <pre>
     *   8 bytes  — block number (long, always 0)
     *   48 bytes — previous block root hash (SHA-384)
     *   8 bytes  — streaming hasher leaf count (long)
     *   4 bytes  — streaming hasher hash count (int)
     *   48 bytes × hash count — pending subtree hashes
     * </pre>
     *
     * <p>Usage: {@code java JumpstartGenerator [output-path]}
     * Default output: {@value #DEFAULT_OUT}
     */
    public static final class JumpstartGenerator {

        private static final String DEFAULT_OUT = "jumpstart.bin";
        private static final int HASH_COUNT = 30;
        private static final int HASH_SIZE = 48;

        public static void main(String[] args) throws Exception {
            final Path out = Path.of(args.length > 0 ? args[0] : DEFAULT_OUT);

            final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0L);
            final var rng = new Random();
            byte[] prevHash = new byte[HASH_SIZE];
            for (int i = 0; i < HASH_COUNT; i++) {
                final var randomHash = new byte[HASH_SIZE];
                rng.nextBytes(randomHash);
                hasher.addNodeByHash(randomHash);
                if (i == HASH_COUNT - 1) {
                    prevHash = randomHash;
                }
            }

            final var baos = new ByteArrayOutputStream();
            try (final var dout = new DataOutputStream(baos)) {
                dout.writeLong(0L); // block number
                dout.write(prevHash);
                dout.writeLong(hasher.leafCount());
                final var intermediateHashes = hasher.intermediateHashingState();
                dout.writeInt(intermediateHashes.size());
                for (final var hash : intermediateHashes) {
                    dout.write(hash.toByteArray());
                }
            }
            Files.write(out, baos.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.printf("Wrote jumpstart file: %s (%d bytes)%n", out.toAbsolutePath(), baos.size());
        }
    }

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

        public static void main(String[] args) throws IOException {
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
