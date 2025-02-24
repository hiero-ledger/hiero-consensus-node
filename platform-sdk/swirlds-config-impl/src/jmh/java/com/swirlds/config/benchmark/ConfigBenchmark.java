// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.benchmark;

import com.swirlds.base.utility.FileSystemUtils;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.PropertyFileConfigSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
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
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class ConfigBenchmark {

    Configuration configuration;

    private FileSystem fileSystem;

    public static void main(final String[] args) throws RunnerException {
        final Options opt = new OptionsBuilder().build();
        new Runner(opt).run();
    }

    @Setup(Level.Iteration)
    public void setup() throws IOException, URISyntaxException {
        final URI configUri =
                ConfigBenchmark.class.getResource("app.properties").toURI();
        fileSystem = FileSystems.newFileSystem(configUri, Collections.emptyMap());
        final Path configFile = Paths.get(configUri);

        if (!FileSystemUtils.waitForPathPresence(configFile)) {
            throw new FileNotFoundException("File not found: " + configFile);
        }

        configuration = ConfigurationBuilder.create()
                .withConfigDataType(AppConfig.class)
                .withSource(new PropertyFileConfigSource(configFile))
                .build();
    }

    @TearDown(Level.Iteration)
    public void reset() {
        try {
            fileSystem.close();
        } catch (IOException ignored) {
            // Intentionally ignored
        }
    }

    @Benchmark
    @Fork(1)
    @Warmup(iterations = 3, time = 2)
    @Measurement(iterations = 5, time = 2)
    @Threads(5)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    public void loadConfiguration(final Blackhole blackhole) {
        final AppConfig appConfig = configuration.getConfigData(AppConfig.class);
        final int version = appConfig.version();
        final String name = appConfig.name();
        blackhole.consume(version);
        blackhole.consume(name);
    }

    @Benchmark
    @Fork(1)
    @Warmup(iterations = 1_000, time = 2)
    @Measurement(iterations = 10_000, time = 2)
    @Threads(5)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode(Mode.SingleShotTime)
    public void loadConfiguration2(final Blackhole blackhole) {
        final AppConfig appConfig = configuration.getConfigData(AppConfig.class);
        final int version = appConfig.version();
        final String name = appConfig.name();
        blackhole.consume(version);
        blackhole.consume(name);
    }

    @ConfigData("app")
    public record AppConfig(String name, int version) {}
}
