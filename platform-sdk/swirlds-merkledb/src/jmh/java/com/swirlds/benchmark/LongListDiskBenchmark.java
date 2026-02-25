// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.merkledb.collections.LongListDisk;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
@Fork(1)
public class LongListDiskBenchmark {

    private static final Random RANDOM = new Random(98765);

    @Param({"100000000"})
    public int fileSize = 100_000_000;

    @Param({"1000000"})
    public int chunkSize = 1_000_000;

    private Path srcFile;

    private Configuration configuration;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withConfigDataType(MerkleDbConfig.class)
                .withConfigDataType(StateCommonConfig.class)
                .withConfigDataType(TemporaryFileConfig.class)
                .withSource(new SimpleConfigSource("merkleDb.longListChunkSize", "" + chunkSize));
        configuration = configurationBuilder.build();
        try (final LongListHeap list = new LongListHeap(1024, fileSize, 0)) {
            list.updateValidRange(0, fileSize - 1);
            for (int i = 0; i < fileSize; i++) {
                list.put(i, i + 1);
            }
            srcFile = Files.createTempFile("LongListDiskBenchmark", "input");
            if (Files.exists(srcFile)) {
                Files.delete(srcFile);
            }
            list.writeToFile(srcFile);
        }
    }

    @TearDown
    public void shutdown() throws IOException {
        Files.delete(srcFile);
    }

    @Benchmark
    public void loadFromFile() throws IOException {
        try (final LongListDisk list = new LongListDisk(srcFile, fileSize, configuration)) {
            if (list.size() != fileSize) {
                throw new RuntimeException("Wrong file size: expected=" + fileSize + " actual=" + list.size());
            }
        }
    }
}
