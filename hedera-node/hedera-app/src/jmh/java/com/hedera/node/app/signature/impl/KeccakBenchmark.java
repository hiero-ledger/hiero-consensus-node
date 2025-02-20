// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.spi.fixtures.Scenarios;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Fork(value = 1, warmups = 3)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class KeccakBenchmark implements Scenarios {
    private static final String DECOMPRESSED_HEX = "52972572d465d016d4c501887b8df303eee3ed602c056b1eb09260dfa0da0"
            + "ab288742f4dc97d9edb6fd946babc002fdfb06f26caf117b9405ed79275763fdb1c";

    private byte[] decompressedKey;

    @Setup(Level.Invocation)
    public void setUp() {
        final var bytes = Scenarios.hexBytes(DECOMPRESSED_HEX);
        decompressedKey = new byte[(int) bytes.length()];
        bytes.getBytes(0, decompressedKey);
    }

    //    @Benchmark
    public void keccakBench(Blackhole blackhole) {
        blackhole.consume(MiscCryptoUtils.keccak256DigestOf(decompressedKey));
    }
}
