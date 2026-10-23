// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.jmh;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler;
import org.hiero.consensus.benchmark.tools.histogram.LatencyRecorder;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures the latency of {@link PlatformSigner#sign(Bytes)}, with which {@link TipsetEventCreator} signs the hash of
 * every event the node creates. Signing adds to the time needed to create each event, and so to the event cadence.
 *
 * <p>The signer is the production one, as the event creator module builds it from the node's keys: libsodium for
 * Ed25519, Bouncy Castle (SHA384withRSA, 3072-bit keys) for RSA. The keys come from {@link KeysAndCertsGenerator},
 * which derives them from the node ID, so every run signs with the same key. The hashes are random bytes from a fixed
 * seed, generated once per trial, with the length of an event hash before the event hash cutover (SHA-384, 48 bytes)
 * and after it (SHA-256, 32 bytes). Signing does not change its input, so every invocation signs the same hashes.
 * Building the signed event around the signature is not measured here; the event creation benchmark covers it.
 *
 * <p>Both signature schemes are deterministic: the same key and data give the same signature. After every invocation,
 * the SHA-256 digest of all signatures is compared with a constant, so a signer that does less work, or signs other
 * data, fails the run.
 *
 * <p>One invocation signs all hashes. The primary score is the mean time per signature. A {@link LatencyRecorder} also
 * times every call, with one timestamp per call ({@link LatencyRecorder#lap()}), so each latency also contains the
 * bookkeeping of the previous call. {@link LatencyProfiler} reports the percentiles and the allocation per call.
 *
 * <p>Each invocation's setup ends with {@code System.gc()}, so usually no garbage collection falls into the measured
 * calls. A pass during which one does is discarded and replaced by the next invocation.
 *
 * <p>Run with {@code -prof org.hiero.consensus.benchmark.tools.histogram.LatencyProfiler}.
 *
 * <h2>Calibration</h2>
 * <ul>
 *   <li>Date / commit: 2026-10-23, edab840b0d</li>
 *   <li>Machine: hl-cn-benchmark-lin, AS-2115GT-HNTR, AMD EPYC 9254 24-Core Processor, JDK 25.0.2, -XX:ActiveProcessorCount=12 -Xms2g -Xmx2g -XX:+AlwaysPreTouch</li>
 *   <li>Parameter sets: digestType=SHA_256,signingSchema=ED25519 / digestType=SHA_256,signingSchema=RSA / digestType=SHA_384,signingSchema=ED25519 / digestType=SHA_384,signingSchema=RSA</li>
 *   <li>Steady state after: 5 s / 0 s / 5 s / 0 s</li>
 *   <li>Noise (latency.mean, CV): 0.25% / 0.19% / 0.22% / 0.19% per 5 s iteration, 0.30% / 0.05% / 0.26% / 0.02% between forks, 0.02% / 0.01% / 0.00% / 0.01% between runs (3 of 3 / 3 of 3 / 3 of 3 / 3 of 3 runs quiet)</li>
 *   <li>Expected detectable regression: 0.43% / 0.10% / 0.38% / 0.08% (95 % CI half-width of one run)</li>
 *   <li>Samples per iteration: 1.4 M / 0.0 M / 1.3 M / 0.0 M; expected stable: mean, p50, p90, p99, p99.9 / mean, p50, p90, p99, p99.9 / mean, p50, p90, p99, p99.9 / mean, p50, p90, p99, p99.9</li>
 *   <li>Runtime: 2.5 / 2.8 / 2.5 / 2.8 min per parameter set</li>
 *   <li>Raw data: https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39725815238, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39725817915, https://github.com/hiero-ledger/hiero-consensus-node/actions/runs/39725820710</li>
 * </ul>
 */
@State(Scope.Thread)
@Fork(
        value = 2,
        jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 2, time = 35)
public class EventSigningBenchmark {

    /** The seed of the random number generator of the hashes. The same seed produces the same hashes. */
    private static final long SEED = 0;

    /** The ID of the signing node. Its keys are derived from it. */
    private static final NodeId NODE_ID = NodeId.of(0);

    /** The number of hashes signed in one invocation. */
    private static final int NUMBER_OF_HASHES = 1_000;

    /** The SHA-256 digest of all signatures of one invocation, per signing schema and digest type of the hashes. */
    private static final Map<String, String> EXPECTED_DIGESTS = Map.of(
            "ED25519/SHA_256", "c8594c8c615340d7092b5e6452f8cbe2411faed71180deeeed6a69063f68b0c5",
            "ED25519/SHA_384", "047637a88caf769c7c8de761f97a34409a130e9f44a40e5c6c021f1865361a83",
            "RSA/SHA_256", "2458dcbe92cc99d18a6e118988f69af22ffae0f42a0c8a4ced155564ed0d544e",
            "RSA/SHA_384", "6335d3f8b87ed899e1f002c9c64dd6ea96eb11b6e2786d236a0ddb65a531cbfc");

    /** The signing schema of the node's key. */
    @Param({"ED25519", "RSA"})
    public SigningSchema signingSchema;

    /** The digest type of the event hashes, which sets their length: SHA-384 before the event hash cutover. */
    @Param({"SHA_256", "SHA_384"})
    public DigestType digestType;

    /** The hashes to sign. */
    private Bytes[] hashes;

    /** The signer under test. */
    private PlatformSigner signer;

    /** The signatures of the current invocation. */
    private Bytes[] signatures;

    /** The latency recorder for the benchmark. */
    private LatencyRecorder recorder;

    /**
     * Generates the keys and the hashes, and creates the signer. This method is called once per trial.
     */
    @Setup(Level.Trial)
    public void generateInputs() {
        final KeysAndCerts keysAndCerts;
        try {
            keysAndCerts = KeysAndCertsGenerator.generate(NODE_ID, signingSchema);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate the keys of node " + NODE_ID, e);
        }
        signer = new PlatformSigner(keysAndCerts);

        final Random random = new Random(SEED);
        hashes = new Bytes[NUMBER_OF_HASHES];
        for (int i = 0; i < NUMBER_OF_HASHES; i++) {
            final byte[] hash = new byte[digestType.digestLength()];
            random.nextBytes(hash);
            hashes[i] = Bytes.wrap(hash);
        }
        signatures = new Bytes[NUMBER_OF_HASHES];
    }

    /**
     * Prepares the benchmark invocation. This method is called once per invocation.
     */
    @Setup(Level.Invocation)
    public void prepareInvocation() {
        Arrays.fill(signatures, null);
        recorder = LatencyProfiler.recorder();

        // Collect the garbage of the previous invocation now, so that it is not collected during the measured calls.
        System.gc();
    }

    /**
     * Verifies that the signatures are the expected ones. This method is called once per invocation.
     */
    @TearDown(Level.Invocation)
    public void verifyInvocation() {
        final String expected = expectedDigest();
        final String actual = digestOfSignatures();
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Expected the signatures of " + signingSchema + " over " + digestType
                    + " hashes to have the digest " + expected + ", but got " + actual);
        }
        recorder = null;
    }

    /**
     * Signs all hashes and records the latency of each call.
     *
     * @param bh the blackhole to consume the results and prevent dead code elimination
     */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    @OperationsPerInvocation(NUMBER_OF_HASHES)
    public void signHashes(final Blackhole bh) {
        recorder.startMeasurement();
        for (int i = 0; i < NUMBER_OF_HASHES; i++) {
            final Bytes signature = signer.sign(hashes[i]);
            // One timestamp per call: each latency also contains the bookkeeping of the previous call.
            recorder.lap();
            signatures[i] = signature;
        }
        recorder.stopMeasurement();
        bh.consume(signatures);
    }

    /**
     * Returns the SHA-256 digest of all signatures of the current invocation, in hex.
     */
    private String digestOfSignatures() {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (final Bytes signature : signatures) {
            if (signature == null) {
                throw new IllegalStateException("Not every hash was signed");
            }
            digest.update(signature.toByteArray());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Returns the expected digest of all signatures for the current parameters, in hex.
     */
    private String expectedDigest() {
        return EXPECTED_DIGESTS.getOrDefault(signingSchema + "/" + digestType, "none");
    }
}
