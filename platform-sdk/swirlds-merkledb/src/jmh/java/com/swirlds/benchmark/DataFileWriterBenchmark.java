// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import static com.swirlds.common.test.fixtures.DataUtils.randomUtf8Bytes;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.merkledb.files.DataFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * This benchmarks can be used to measure performance of {@link DataFileWriter} class. <p>
 * The following parameters are used to configure the benchmark:
 * <ul>
 *   <li><b>bufferSizeMb</b>: Size of the buffer in MB.</li>
 *   <li><b>maxFileSizeMb</b>: Maximum size of the file to write in MB.</li>
 *   <li><b>sampleSize</b>: Number of sample data items to generate.</li>
 *   <li><b>sampleRangeBytes</b>: Range of the sample data items in bytes, chosen randomly from the sample.</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class DataFileWriterBenchmark {

    private static final int MEGABYTE = 1024 * 1024;

    @Param({"16", "64", "128", "256"})
    public int bufferSizeMb;

    @Param({"50", "200", "500"})
    public int maxFileSizeMb;

    @Param({"10"})
    public int sampleSize;

    // first is test for small data items like hashes
    @Param({"56-56", "300-1000"})
    public String sampleRangeBytes;

    // Runtime variables
    private Random random;
    private BufferedData[] sampleData;
    private long maxFileSize;

    private Path benchmarkDir;
    private DataFileWriter dataFileWriter;

    @Setup(Level.Trial)
    public void setupGlobal() throws IOException {
        // Initialize random generator
        random = new Random(1234);
        maxFileSize = maxFileSizeMb * MEGABYTE;
        benchmarkDir = Files.createTempDirectory("dataFileWriterBenchmark");

        // Generate sample data
        String[] range = sampleRangeBytes.split("-");
        int sampleMinLength = Integer.parseInt(range[0]);
        int sampleMaxLength = Integer.parseInt(range[1]);

        sampleData = new BufferedData[sampleSize];
        for (int i = 0; i < sampleSize; i++) {
            sampleData[i] = BufferedData.wrap(randomUtf8Bytes(random.nextInt(sampleMinLength, sampleMaxLength + 1)));
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
        dataFileWriter = new DataFileWriter("test", benchmarkDir, 1, Instant.now(), 1, bufferSizeMb * MEGABYTE);
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws IOException {
        dataFileWriter.finishWriting();
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
            data = sampleData[random.nextInt(sampleSize)];
            fileSize += data.length();
            if (fileSize > maxFileSize) {
                break;
            }

            dataFileWriter.storeDataItem(data);
            data.flip();
        }
    }
}
