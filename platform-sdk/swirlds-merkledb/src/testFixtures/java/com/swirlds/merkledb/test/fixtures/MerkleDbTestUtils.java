// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.base.units.UnitConstants;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.MerklePathUtils;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;

public class MerkleDbTestUtils {

    public static final String DEFAULT_TABLE_NAME = "testTable";

    /**
     * The amount of direct memory used by JVM and caches. This needs to be big enough to allow for
     * variations in test runs while being small enough to catch leaks in tests.
     */
    private static final long DIRECT_MEMORY_BASE_USAGE = 4 * UnitConstants.MEBIBYTES_TO_BYTES;

    public static final Configuration DEFAULT_CONFIGURATION =
            ConfigurationBuilder.create().autoDiscoverExtensions().build();

    public static final MerkleDbConfig DEFAULT_MERKLE_DB_CONFIG =
            DEFAULT_CONFIGURATION.getConfigData(MerkleDbConfig.class);

    /**
     * Run a callable test in the background and then make sure no direct memory is leaked and not
     * databases are left open. Running test in a thread helps by allowing the thread to be killed
     * so we can clean up any thread local cached data used in the test.
     *
     * @param callable The test to run
     * @throws Exception If there was a problem running the test
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void runTaskAndCleanThreadLocals(final Callable callable) throws Exception {
        // Keep track of direct memory used already, so we can check if we leek over and above what
        // we started with
        final long directMemoryUsedAtStart = getDirectMemoryUsedBytes();
        // run test in background thread
        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final var future = executorService.submit(callable);
        future.get(300, TimeUnit.SECONDS);
        executorService.shutdown();
        // Check we did not leak direct memory now that the thread is shut down so thread locals
        // should be released
        checkDirectMemoryIsCleanedUpToLessThanBaseUsage(directMemoryUsedAtStart);
        assertAllDatabasesClosed();
    }

    /**
     * Check if direct memory used is less than base usage, calling gc() up to 20 times to try and
     * clean it up, checking each time. The limit of was chosen as big enough to not be effected by
     * any JVM internal use of direct memory and any cache maintained by sun.nio.ch.Util.
     *
     * <p><b>It is possible this is non-deterministic, because gc() is not guaranteed to free memory
     * and is async.</b>
     *
     * @param directMemoryBytesBefore The number of bytes of direct memory allocated before test was
     *     started
     */
    public static void checkDirectMemoryIsCleanedUpToLessThanBaseUsage(final long directMemoryBytesBefore) {
        final long limit = directMemoryBytesBefore + DIRECT_MEMORY_BASE_USAGE;
        if (getDirectMemoryUsedBytes() < limit) {
            return;
        }
        for (int i = 0; i < 5 && getDirectMemoryUsedBytes() > limit; i++) {
            System.gc();
            try {
                SECONDS.sleep(1);
            } catch (final InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        assertTrue(
                getDirectMemoryUsedBytes() < limit,
                "Direct Memory used is more than base usage even after 5 gc() calls. At start was "
                        + (directMemoryBytesBefore * UnitConstants.BYTES_TO_MEBIBYTES) + "MB and is now "
                        + (getDirectMemoryUsedBytes() * UnitConstants.BYTES_TO_MEBIBYTES)
                        + "MB");
    }

    private static final BufferPoolMXBean DIRECT_MEMORY_POOL;

    static {
        //noinspection OptionalGetWithoutIsPresent
        DIRECT_MEMORY_POOL = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct"))
                .findFirst()
                .get();
    }

    /** Get the amount of direct memory used in bytes */
    public static long getDirectMemoryUsedBytes() {
        return DIRECT_MEMORY_POOL.getMemoryUsed();
    }

    public static Stream<VirtualHashChunk> createHashChunkStream(
            final int minPathInc,
            final int maxPathInc,
            final Function<Integer, Integer> valueFunction,
            final int hashChunkHeight) {
        final int firstLeafPath = maxPathInc / 2;
        final Map<Long, VirtualHashChunk> chunks = new HashMap<>();
        for (int i = minPathInc; i <= maxPathInc; i++) {
            if (i == 0) {
                // No hash chunk for path 0
                continue;
            }
            final long chunkId = VirtualHashChunk.pathToChunkId(i, hashChunkHeight);
            VirtualHashChunk chunk = chunks.get(chunkId);
            if (chunk == null) {
                chunk = new VirtualHashChunk(
                        VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight), hashChunkHeight);
                chunks.put(chunkId, chunk);
            }
            final boolean isLeaf = i >= firstLeafPath;
            final int rank = MerklePathUtils.getRank(i);
            if (isLeaf || (rank % hashChunkHeight == 0)) {
                chunk.setHashAtPath(i, hash(valueFunction.apply(i)));
            }
        }
        return chunks.values().stream().sorted(Comparator.comparingLong(VirtualHashChunk::path));
    }

    public static Stream<VirtualHashChunk> createHashChunkStream(final int lastLeafPath, final int hashChunkHeight) {
        return createHashChunkStream(1, lastLeafPath, i -> i, hashChunkHeight);
    }

    /**
     * Creates a hash containing an int repeated 6 times as longs.
     *
     * @return hash with digest an array of 6 longs determined by the given value
     */
    public static Hash hash(final int value) {
        final byte[] hardCoded =
                new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        final byte[] digest = new byte[DigestType.SHA_384.digestLength()];
        for (int i = 0; i < 6; i++) {
            System.arraycopy(hardCoded, 0, digest, i * 6 + 4, 4);
        }
        return new Hash(digest, DigestType.SHA_384);
    }

    /** Code from method java.util.Collections.shuffle(); */
    public static int[] shuffle(Random random, final int[] array) {
        if (random == null) {
            random = new Random();
        }
        final int count = array.length;
        for (int i = count; i > 1; i--) {
            swap(array, i - 1, random.nextInt(i));
        }
        return array;
    }

    private static void swap(final int[] array, final int i, final int j) {
        final int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    public static byte[] randomUtf8Bytes(final int n) {
        final byte[] data = new byte[n];
        int i = 0;
        while (i < n) {
            final byte[] rnd = UUID.randomUUID().toString().getBytes();
            System.arraycopy(rnd, 0, data, i, Math.min(rnd.length, n - 1 - i));
            i += rnd.length;
        }
        return data;
    }

    public static Metrics createMetrics() {
        final MetricsConfig metricsConfig = DEFAULT_CONFIGURATION.getConfigData(MetricsConfig.class);
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        return new DefaultPlatformMetrics(
                null,
                registry,
                mock(ScheduledExecutorService.class),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }

    /**
     * Extract a statistic from the data source. Not very efficient, but good enough for a unit test.
     */
    public static Metric getMetric(final Metrics metrics, final VirtualDataSource dataSource, final String pattern) {
        return getMetric(metrics, dataSource, pattern, false);
    }

    /**
     * Extract a statistic from the data source. Not very efficient, but good enough for a unit test.
     */
    public static Metric getMetric(
            final Metrics metrics,
            final VirtualDataSource dataSource,
            final String pattern,
            final boolean mayNotExist) {
        dataSource.registerMetrics(metrics);
        final Optional<Metric> metric = metrics.getAll().stream()
                .filter(it -> it.getName().contains(pattern))
                .findAny();

        if (!mayNotExist && metric.isEmpty()) {
            throw new IllegalStateException("unable to find statistic containing pattern " + pattern);
        }

        return metric.orElse(null);
    }

    /**
     * Asserts that all databases are closed within a certain time frame.
     */
    public static void assertAllDatabasesClosed() {
        assertSomeDatabasesStillOpen(0L);
    }

    /**
     * Asserts that the number of open databases matches the expected count within a specified time frame.
     *
     * @param expectedOpenCount The expected number of open databases to validate.
     */
    public static void assertSomeDatabasesStillOpen(@NonNull final Long expectedOpenCount) {
        assertEventuallyEquals(
                expectedOpenCount,
                MerkleDbDataSourceBuilder::getCountOfOpenDatabases,
                Duration.of(5, ChronoUnit.SECONDS),
                "Expected " + expectedOpenCount + " open databases.");
    }

    /**
     * Asserts that no MerkleDb data source storage directory is left behind in the given directory,
     * which is expected to be the temp dir of a {@code FileSystemManager}.
     *
     * <p>Unlike {@link #assertAllDatabasesClosed()}, which only reads the open data source counter,
     * this looks at the file system, so it also catches a data source that was closed but failed to
     * remove its storage dir. Such a directory may well be empty by then, which is why this matches
     * on the name rather than on any file inside it.
     *
     * @param tempDir the directory that data source storage dirs are created in
     */
    public static void assertNoDatabaseFolders(@NonNull final Path tempDir) {
        final List<Path> databaseFolders;
        try (final Stream<Path> entries = Files.list(tempDir)) {
            databaseFolders = entries.filter(Files::isDirectory)
                    // 'contains', not 'startsWith': FileSystemManager.resolveNewTemp() prefixes the
                    // tag with a timestamp and a counter, so the marker sits in the middle of the
                    // name. Using 'startsWith' here would match nothing and pass vacuously.
                    .filter(p -> p.getFileName().toString().contains(MerkleDbDataSourceBuilder.FOLDER_PREFIX))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list temp directory: " + tempDir, e);
        }
        assertTrue(databaseFolders.isEmpty(), "Database folders should have been deleted: " + databaseFolders);
    }

    /**
     * Returns the directory within {@code snapshotDir} that holds the data source files, matching
     * the layout {@link MerkleDbDataSourceBuilder} writes and reads.
     */
    public static Path snapshotDataDir(@NonNull final Path snapshotDir, @NonNull final String label) {
        return snapshotDir.resolve("data").resolve(label);
    }
}
