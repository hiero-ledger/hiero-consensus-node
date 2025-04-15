// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.swirlds.benchmark.config.BenchmarkConfig;
import com.swirlds.benchmark.reconnect.BenchmarkMerkleInternal;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.export.ConfigExport;
import com.swirlds.config.extensions.sources.LegacyFileConfigSource;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Timeout;

@State(Scope.Benchmark)
@Timeout(time = Integer.MAX_VALUE)
public abstract class BaseBench {

    private static final Logger logger = LogManager.getLogger(BaseBench.class);

    protected static final String RUN_DELIMITER = "--------------------------------";

    @Param({"100"})
    public int numFiles = 500;

    @Param({"100000"})
    public int numRecords = 10_000;

    @Param({"1000000"})
    public int maxKey = 10_000_000;

    @Param({"8"})
    public int keySize = 32;

    @Param({"128"})
    public int recordSize = 1024;

    @Param({"32"})
    public int numThreads = 32;

    abstract String benchmarkName();

    private static final int SKEW = 2;
    private static final int RECORD_SIZE_MIN = 8;

    /* Directory for the entire benchmark */
    private static Path benchDir;
    /* Directory for each iteration */
    private Path testDir;
    /* Verify benchmark results */
    protected boolean verify;

    protected static Configuration configuration;

    private static void loadConfig() throws IOException {
        ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withSource(new LegacyFileConfigSource(Path.of(".", "settings.txt")))
                .withConfigDataType(BenchmarkConfig.class)
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(MetricsConfig.class)
                .withConfigDataType(CryptoConfig.class);
        configuration = configurationBuilder.build();

        final StringBuilder settingsUsed = new StringBuilder();
        ConfigExport.addConfigContents(configuration, settingsUsed);
        try (OutputStream os = Files.newOutputStream(Path.of(".", "settingsUsed.txt"))) {
            os.write(settingsUsed.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Setup
    public void setup() throws IOException {
        loadConfig();
        final BenchmarkConfig benchmarkConfig = getConfig(BenchmarkConfig.class);
        logger.info("Benchmark configuration: {}", benchmarkConfig);
        logger.info("Build: {}", Utils.buildVersion());

        final String data = benchmarkConfig.benchmarkData();
        if (data == null || data.isBlank()) {
            benchDir = Files.createTempDirectory(benchmarkName());
        } else {
            benchDir = Files.createDirectories(Path.of(data).resolve(benchmarkName()));
        }

        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(benchDir.resolve("tmp"));

        try {
            final ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.registerConstructables("com.swirlds.virtualmap");
            registry.registerConstructables("com.swirlds.virtualmap.datasource");
            registry.registerConstructables("com.swirlds.virtualmap.internal.merkle");
            registry.registerConstructables("com.swirlds.merkledb");
            registry.registerConstructables("com.swirlds.benchmark");
            registry.registerConstructables("com.swirlds.common.crypto");
            registry.registerConstructables("com.swirlds.common");
            registry.registerConstructables("org.hiero.consensus");
            registry.registerConstructable(
                    new ClassConstructorPair(BenchmarkMerkleInternal.class, BenchmarkMerkleInternal::new));
            registry.registerConstructable(new ClassConstructorPair(BenchmarkKey.class, BenchmarkKey::new));
            registry.registerConstructable(new ClassConstructorPair(BenchmarkValue.class, BenchmarkValue::new));
        } catch (ConstructableRegistryException ex) {
            logger.error("Failed to construct registry", ex);
        }

        verify = benchmarkConfig.verifyResult();

        BenchmarkKey.setKeySize(keySize);

        // recordSize = keySize + valueSize
        BenchmarkValue.setValueSize(Math.max(recordSize - keySize, RECORD_SIZE_MIN));

        if (numThreads <= 0) {
            numThreads = ForkJoinPool.getCommonPoolParallelism();
        }

        // Setup metrics system
        BenchmarkMetrics.start(benchmarkConfig);
    }

    @TearDown
    public void destroy() {
        BenchmarkMetrics.stop();
        if (!getBenchmarkConfig().saveDataDirectory()) {
            Utils.deleteRecursively(benchDir);
        }
    }

    @Setup(Level.Invocation)
    public void beforeTest() {
        BenchmarkMetrics.reset();
    }

    public void beforeTest(String name) {
        setTestDir(name);
    }

    public static Path getBenchDir() {
        return benchDir;
    }

    public Path getTestDir() {
        return testDir;
    }

    public void setTestDir(String name) {
        testDir = benchDir.resolve(name);
    }

    interface RunnableWithException {
        void run() throws Exception;
    }

    public void afterTest() throws Exception {
        afterTest(false, null);
    }

    public void afterTest(boolean keepTestDir) throws Exception {
        afterTest(keepTestDir, null);
    }

    public void afterTest(RunnableWithException runnable) throws Exception {
        afterTest(false, runnable);
    }

    public void afterTest(boolean keepTestDir, RunnableWithException runnable) throws Exception {
        BenchmarkMetrics.report();
        if (getBenchmarkConfig().printHistogram()) {
            // Class histogram is interesting before closing
            Utils.printClassHistogram(15);
        }
        if (runnable != null) {
            runnable.run();
        }
        if (!keepTestDir) {
            Utils.deleteRecursively(testDir);
        }
    }

    private long currentKey;
    private long currentRecord;

    protected void resetKeys() {
        currentKey = -1L;
        currentRecord = 0L;
    }

    /**
     * Randomly select next key id in ascending order.
     * numRecords values will be uniformly distributed between 0 and maxKey when SKEW == 1.
     * With larger SKEW, more values will be selected from the lower half of the interval.
     *
     * @return Next key id > lastKey and < maxKey
     */
    protected long nextAscKey() {
        for (; ; ) {
            if (Utils.randomLong(maxKey - ++currentKey) < (numRecords - currentRecord) * SKEW) {
                ++currentRecord;
                return currentKey;
            }
        }
    }

    /**
     * Return next random value id
     *
     * @return Next value id
     */
    protected long nextValue() {
        return Utils.randomLong();
    }

    public <T extends Record> T getConfig(Class<T> configCls) {
        return configuration.getConfigData(configCls);
    }

    public BenchmarkConfig getBenchmarkConfig() {
        return getConfig(BenchmarkConfig.class);
    }
}
