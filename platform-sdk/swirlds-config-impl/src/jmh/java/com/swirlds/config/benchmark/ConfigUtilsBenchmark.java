// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.benchmark;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import java.nio.file.FileSystem;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class ConfigUtilsBenchmark {

    Configuration configuration;

    private FileSystem fileSystem;

    public static void main(final String[] args) throws RunnerException {
        final Options opt = new OptionsBuilder().build();
        new Runner(opt).run();
    }

    @Benchmark
    @Fork(1)
    @Warmup(iterations = 3, time = 2)
    @Measurement(iterations = 5, time = 2)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    public void loadConfigurationNoScan(final Blackhole blackhole) {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
        final ConfigurationBuilder modified = configurationBuilder.withConfigDataType(AppConfig.class);
        blackhole.consume(modified);
    }

    @Benchmark
    @Fork(1)
    @Warmup(iterations = 3, time = 2)
    @Measurement(iterations = 5, time = 2)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @BenchmarkMode({Mode.Throughput, Mode.SampleTime})
    public void loadConfigurationWithAllPackages(final Blackhole blackhole) {
        final ConfigurationBuilder configurationBuilder = ConfigurationBuilder.create();
        final ConfigurationBuilder modified = configurationBuilder.autoDiscoverExtensions();
        blackhole.consume(modified);
    }

    @ConfigData("app")
    public record AppConfig(String name, int version) {}
}
