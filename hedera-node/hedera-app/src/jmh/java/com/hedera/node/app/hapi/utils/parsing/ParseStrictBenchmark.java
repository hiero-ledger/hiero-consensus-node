// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.parsing;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.utils.TransactionGeneratorUtil;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Compares Codec.parse vs Codec.parseStrict for the payload types touched by commit 646984b8 ("feat(parsing): migrate
 * protobuf parsing to parseStrict ...").
 * <p>
 * Hypothesis under test: parseStrict has different CPU cost than parse on well-formed payloads. Expected outcome: no
 * measurable difference. parseStrict is parse(strictMode=true), and strictMode only diverges from non-strict on unknown
 * field numbers (PBJ Codec.java:158-189).
 */
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ParseStrictBenchmark {

    @Param({"200", "1000", "10000"})
    private int transactionSizeBytes;

    private Bytes txBodyBytes;
    private Bytes signedTxBytes;
    private Bytes keyBytes;
    private Bytes exchangeRateSetBytes;

    @Setup(Level.Trial)
    public void setup() throws ParseException {
        txBodyBytes = TransactionGeneratorUtil.generateTransaction(transactionSizeBytes);

        final var signedTx =
                SignedTransaction.newBuilder().bodyBytes(txBodyBytes).build();
        signedTxBytes = SignedTransaction.PROTOBUF.toBytes(signedTx);

        final var key = Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
        keyBytes = Key.PROTOBUF.toBytes(key);

        final var rates = ExchangeRateSet.newBuilder()
                .currentRate(ExchangeRate.newBuilder().centEquiv(12).hbarEquiv(1).build())
                .nextRate(ExchangeRate.newBuilder().centEquiv(13).hbarEquiv(1).build())
                .build();
        exchangeRateSetBytes = ExchangeRateSet.PROTOBUF.toBytes(rates);

        // Sanity: make sure both paths agree before benchmarking.
        sanity(TransactionBody.PROTOBUF, txBodyBytes);
        sanity(SignedTransaction.PROTOBUF, signedTxBytes);
        sanity(Key.PROTOBUF, keyBytes);
        sanity(ExchangeRateSet.PROTOBUF, exchangeRateSetBytes);
    }

    private static <T> void sanity(final Codec<T> codec, final Bytes bytes) throws ParseException {
        final var a = codec.parse(bytes);
        final var b = codec.parseStrict(bytes);
        if (!a.equals(b)) {
            throw new IllegalStateException("parse/parseStrict disagree for " + codec.getClass().getSimpleName());
        }
    }

    // --- TransactionBody ----------------------------------------------------

    @Benchmark
    public void transactionBody_parse(final Blackhole bh) throws ParseException {
        bh.consume(TransactionBody.PROTOBUF.parse(txBodyBytes));
    }

    @Benchmark
    public void transactionBody_parseStrict(final Blackhole bh) throws ParseException {
        bh.consume(TransactionBody.PROTOBUF.parseStrict(txBodyBytes));
    }

    // --- SignedTransaction --------------------------------------------------

    @Benchmark
    public void signedTransaction_parse(final Blackhole bh) throws ParseException {
        bh.consume(SignedTransaction.PROTOBUF.parse(signedTxBytes));
    }

    @Benchmark
    public void signedTransaction_parseStrict(final Blackhole bh) throws ParseException {
        bh.consume(SignedTransaction.PROTOBUF.parseStrict(signedTxBytes));
    }

    // --- Key ---------------------------------------------------------------

    @Benchmark
    public void key_parse(final Blackhole bh) throws ParseException {
        bh.consume(Key.PROTOBUF.parse(keyBytes));
    }

    @Benchmark
    public void key_parseStrict(final Blackhole bh) throws ParseException {
        bh.consume(Key.PROTOBUF.parseStrict(keyBytes));
    }

    // --- ExchangeRateSet ---------------------------------------------------

    @Benchmark
    public void exchangeRateSet_parse(final Blackhole bh) throws ParseException {
        bh.consume(ExchangeRateSet.PROTOBUF.parse(exchangeRateSetBytes));
    }

    @Benchmark
    public void exchangeRateSet_parseStrict(final Blackhole bh) throws ParseException {
        bh.consume(ExchangeRateSet.PROTOBUF.parseStrict(exchangeRateSetBytes));
    }
}
