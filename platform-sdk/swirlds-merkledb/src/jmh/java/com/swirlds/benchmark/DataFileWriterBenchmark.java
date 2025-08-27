// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.base.test.fixtures.util.DataUtils;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.merkledb.files.DataFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * This benchmarks can be used to measure performance of {@link DataFileWriter} class.
 * It uses pregenerated list of data items to measure only writing speed.
 */
@State(Scope.Benchmark)
public class DataFileWriterBenchmark {

    /**
     * Size of the buffer in MB - <b>should not</b> impact on performance with the current implementation with mapped buffer.
     */
    @Param({"16", "64", "128", "256"})
    public int bufferSizeMb;

    /**
     * Maximum size of the file to write in MB.
     */
    @Param({"50", "200", "500"})
    public int maxFileSizeMb;

    /**
     * Number of sample data items to pre-generate before each iteration.
     */
    @Param({"10"})
    public int sampleSize;

    /**
     * Range of the sample data items in bytes, chosen randomly from the sample.
     */
    // first is test for small data items like hashes
    @Param({"56-56", "300-1000", "1024-8192"})
    public String sampleRangeBytes;

    // Runtime variables
    private Random random;
    private BufferedData[] sampleData;
    private long maxFileSize;

    private Path benchmarkDir;
    private DataFileWriter dataFileWriter;

    @Setup(Level.Trial)
    public void setupGlobal() throws IOException {
        random = new Random(1234);
        benchmarkDir = Files.createTempDirectory("dataFileWriterBenchmark");

        maxFileSize = maxFileSizeMb * MEBIBYTES_TO_BYTES;

        // Generate sample data
        String[] range = sampleRangeBytes.split("-");
        int sampleMinLength = Integer.parseInt(range[0]);
        int sampleMaxLength = Integer.parseInt(range[1]);

        sampleData = new BufferedData[sampleSize];
        for (int i = 0; i < sampleSize; i++) {
            sampleData[i] =
                    BufferedData.wrap(DataUtils.randomUtf8Bytes(random.nextInt(sampleMinLength, sampleMaxLength + 1)));
        }

        System.out.println("Sample data sizes in bytes: "
                + Arrays.toString(Arrays.stream(sampleData)
                        .mapToLong(BufferedData::length)
                        .toArray()));
    }

    @TearDown(Level.Trial)
    public void tearDownGlobal() throws IOException {
        if (benchmarkDir != null) {
            FileUtils.deleteDirectory(benchmarkDir);
        }
    }

    @Setup(Level.Invocation)
    public void setup() throws IOException {
        dataFileWriter =
                new DataFileWriter("test", benchmarkDir, 1, Instant.now(), 1, bufferSizeMb * MEBIBYTES_TO_BYTES);
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws IOException {
        dataFileWriter.close();
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @Measurement(iterations = 1, time = 3, timeUnit = TimeUnit.SECONDS)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(value = 1, warmups = 0)
    @Warmup(iterations = 0)
    public void writeInFile() throws IOException {
        long fileSize = 0;
        BufferedData data;

        while (true) {
            data = getRandomData();
            fileSize += data.length();
            if (fileSize > maxFileSize) {
                break;
            }

            dataFileWriter.storeDataItem(data);
            data.flip();
        }
    }

    private BufferedData getRandomData() {
        return sampleData[random.nextInt(sampleSize)];
    }
}
