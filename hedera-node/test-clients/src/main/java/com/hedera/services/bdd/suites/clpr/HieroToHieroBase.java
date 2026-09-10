// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Function;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.extensions.MultiNetworkExtension;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.utilops.grouping.ParallelSpecOps;
import com.hederahashgraph.api.proto.java.ClprEndpoint;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprServiceEndpoint;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.math.ec.rfc8032.Ed25519;
import org.junit.jupiter.api.DynamicTest;

/**
 * Shared scaffolding for multi-network Hiero-to-Hiero CLPR HAPI suites: contract/contract
 * names, gas/stake constants, polling helpers, ledger-configuration builder, and a
 * deterministic Ed25519 {@link ClprCrypto} fixture for channel + connector setup.
 *
 *
 *
 *
 * <p>Subclasses provide the actual {@code @MultiNetworkHapiTest} methods.
 */
public abstract class HieroToHieroBase {
    private static final Logger log = LogManager.getLogger(HieroToHieroBase.class);

    // Registry name under which the CLPR system contract precompile (0x16e) is pre-registered
    // and used as the verifier contract — the Java-side dispatch already targets the precompile's
    // own verifyConfig(bytes) / verifyBundle(bytes,bytes) registered methods, so a Solidity proxy
    // is unnecessary.
    static final String VERIFIER = "ClprSystemVerifier";
    static final long CLPR_SYSTEM_CONTRACT_NUM = 0x16eL;
    static final String CONNECTOR_CONTRACT = "PassThroughAuth";
    static final String CLPR_CONTRACT = "ClprSystemContract";
    static final String SEND_MESSAGE = "sendMessage";
    static final String GET_QUEUE_STATE = "getChannelQueueState";
    static final long GAS = 2_000_000L;
    static final long MIN_STAKE = 100L;
    /** How long {@link #awaitReceivedMessage} / {@link #awaitAckedMessage} wait for delivery once WRAPS is warm. */
    static final Duration DELIVERY_TIMEOUT = Duration.ofMinutes(1);

    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    /** Matches the {@link #buildLedgerConfig} default — used by overloads that only need to tune sync rate. */
    static final int DEFAULT_MAX_MESSAGES_PER_BUNDLE = 100;
    /** Matches the {@link #buildLedgerConfig} default — used by overloads that only need to tune sync rate. */
    static final int DEFAULT_MAX_QUEUE_DEPTH = 1000;

    /**
     * How long {@link #awaitWrapsExtensible} waits for the first WRAPS-extensible recursive proof.
     * Empirically a single-node subprocess network finishes the first extensible construction
     * roughly 12–18 minutes after startup; 25 minutes leaves slack.
     */
    static final Duration WRAPS_EXTENSIBLE_TIMEOUT = Duration.ofMinutes(25);

    static final Pattern WRAPS_EXTENSIBLE_PATTERN =
            Pattern.compile("History proof constructed \\(#\\d+, WRAPS-extensible\\? true\\)");

    /**
     * Substring logged by {@code TssStartupNetworks.initializeHistoryState} when the dev-only
     * history startup state has been preloaded from a captured fixture (warm-cache path).
     * Equivalent end-state to a runtime-constructed WRAPS-extensible proof, BUT — unlike the
     * runtime log line — fires at JVM startup, before any block has been signed. So matching
     * this alone is not enough; {@link #awaitWrapsExtensible} chains {@link #awaitWrapsSyncPoint}
     * after this match so the warm path doesn't return until a real WRAPS-carrying block proof
     * has materialized.
     */
    static final Pattern WRAPS_PRELOADED_PATTERN = Pattern.compile(
            "TssStartupNetworks - Initialized dev-only history startup state:.*hasChainOfTrustProof=true");

    /**
     * One-shot log line emitted by {@code BlockStreamManagerImpl.finishProofWithSignature} when
     * the first block proof embeds the WRAPS recursive proof. After this fires, captured
     * {@code clprGetLedgerConfiguration} state proofs reference WRAPS-carrying state and the
     * peer's {@code NativeTssVerifier} will accept them on cross-network verify.
     */
    static final Pattern WRAPS_SYNC_POINT_PATTERN =
            Pattern.compile("\\[CLPR-SYNC-POINT\\] block #\\d+ is the first to embed the WRAPS recursive proof");

    /** Upper bound on wait for the {@code [CLPR-SYNC-POINT]} log after WRAPS-extensible. */
    static final Duration WRAPS_SYNC_POINT_TIMEOUT = Duration.ofMinutes(3);
    /** Small settle after the sync-point so a few WRAPS-carrying blocks accumulate before capture. */
    public static final Duration POST_SYNC_POINT_SETTLE = Duration.ofSeconds(5);

    // ── Shared setup helper ───────────────────────────────────────────────────

    /** Distinguishes how WRAPS-extensible readiness was reached, so callers can settle only when needed. */
    public enum WrapsReadiness {
        /** Runtime-constructed at this JVM start (cold path) — proof needs settle to fully bake before peers can verify. */
        COLD_CONSTRUCTED,
        /** Loaded from a captured fixture (warm path) — already mature, no settle required. */
        WARM_PRELOADED
    }

    /**
     * Blocks until this network is ready to produce signatures that cross-network bundle
     * verification will accept. Returns when BOTH conditions hold:
     *
     * <ol>
     *   <li>The history proof construction is WRAPS-extensible — either freshly built at
     *       runtime ({@code History proof constructed (#N, WRAPS-extensible? true)} log line,
     *       cold path) or loaded from a captured fixture ({@code TssStartupNetworks -
     *       Initialized dev-only history startup state: ... hasChainOfTrustProof=true},
     *       warm-cache path); AND</li>
     *   <li>The first block proof embeds the WRAPS recursive material (the one-shot
     *       {@code [CLPR-SYNC-POINT]} log emitted by {@code BlockStreamManagerImpl}). On
     *       cold path this fires shortly after the runtime construction event; on warm
     *       path the preload log fires at JVM startup BEFORE any block is signed, so this
     *       wait is what keeps the downstream {@link #captureConfigProof} from polling
     *       against an empty StateProof.</li>
     * </ol>
     *
     * <p>Returns which readiness path was taken; the caller decides whether to settle.
     *
     * <p>Cold-path latency: ~12-18 min (waits for runtime WRAPS construction).
     * Warm-path latency: ~few seconds (preload log fires immediately, then waits for the
     * first WRAPS-carrying block — usually within 1-5 sec of network ACTIVE).
     */
    public static WrapsReadiness awaitWrapsExtensible(final SubProcessNetwork network) {
        final Path logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        final Instant deadline = Instant.now().plus(WRAPS_EXTENSIBLE_TIMEOUT);
        Instant nextProgressLog = Instant.now().plus(Duration.ofMinutes(1));
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logPath)) {
                try (var lines = Files.lines(logPath)) {
                    // Match either: (a) runtime-constructed WRAPS-extensible proof (cold path,
                    // implies blocks have been signed), or (b) preloaded WRAPS state from a
                    // cached fixture (warm path, fires at JVM startup before any block).
                    final var matched = lines.map(line -> {
                                if (WRAPS_EXTENSIBLE_PATTERN.matcher(line).find()) {
                                    return WrapsReadiness.COLD_CONSTRUCTED;
                                }
                                if (WRAPS_PRELOADED_PATTERN.matcher(line).find()) {
                                    return WrapsReadiness.WARM_PRELOADED;
                                }
                                return null;
                            })
                            .filter(r -> r != null)
                            .findFirst();
                    if (matched.isPresent()) {
                        // Ensure the first WRAPS-carrying block proof has materialized before
                        // returning. On warm path this also serves as the "at least one block
                        // signed" gate — the preload log fires at JVM startup, and
                        // [CLPR-SYNC-POINT] is the first signal that a real block proof has
                        // been produced with WRAPS material attached.
                        awaitWrapsSyncPoint(network);
                        return matched.get();
                    }
                } catch (final IOException ignored) {
                    // log may be mid-rotation; retry
                }
            }
            if (Instant.now().isAfter(nextProgressLog)) {
                System.err.println("[clpr-test] still waiting for WRAPS-extensible proof on '" + network.name()
                        + "' (deadline " + deadline + ")");
                nextProgressLog = nextProgressLog.plus(Duration.ofMinutes(1));
            }
            try {
                Thread.sleep(2000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("Network '" + network.name()
                + "' did not produce a WRAPS-extensible history proof within " + WRAPS_EXTENSIBLE_TIMEOUT);
    }

    /**
     * Polls {@code hgcaa.log} for the {@code [CLPR-SYNC-POINT]} line emitted by
     * {@code BlockStreamManagerImpl.finishProofWithSignature}. The line is guarded by an
     * {@code AtomicBoolean.compareAndSet(false, true)} in {@code BlockStreamManagerImpl}, so
     * it fires <b>exactly once per process</b>: on the first signed block whose proof
     * embeds the WRAPS recursive material.
     *
     * <p>Both bootstrap paths require this gate:
     * <ul>
     *   <li><b>Cold (TSS metadata generation)</b> — the runtime
     *       {@code History proof constructed (..., WRAPS-extensible? true)} log fires once
     *       the history+TSS state is ready, but it can take an additional block or two
     *       before that material actually shows up inside a block proof. {@code [CLPR-SYNC-POINT]}
     *       is the deterministic moment cross-network state proofs become peer-verifiable.</li>
     *   <li><b>Warm (TSS preload)</b> — the {@code TssStartupNetworks} preload log fires at
     *       JVM startup, before any block has been signed. {@code [CLPR-SYNC-POINT]} marks
     *       the first real signed block carrying WRAPS data, which is what downstream
     *       captures actually need.</li>
     * </ul>
     *
     * <p>Used in place of a fixed wall-clock settle after {@code WRAPS-extensible? true}.
     */
    public static void awaitWrapsSyncPoint(final SubProcessNetwork network) {
        final Path logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        final Instant deadline = Instant.now().plus(WRAPS_SYNC_POINT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logPath)) {
                try (var lines = Files.lines(logPath)) {
                    if (lines.anyMatch(
                            line -> WRAPS_SYNC_POINT_PATTERN.matcher(line).find())) {
                        return;
                    }
                } catch (final IOException ignored) {
                    // log may be mid-rotation; retry
                }
            }
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException("Network '" + network.name()
                + "' did not emit [CLPR-SYNC-POINT] within " + WRAPS_SYNC_POINT_TIMEOUT
                + " — first WRAPS-carrying block proof never reached BlockStreamManagerImpl");
    }

    /**
     * Polls the network's {@code hgcaa.log} until a line matching {@code pattern} is found, or
     * throws after {@code timeout}. Used to assert handler-emitted log markers (e.g. step10
     * ConfigUpdate application on the peer).
     */
    public static void awaitLogLine(final SubProcessNetwork network, final Pattern pattern, final Duration timeout) {
        final Path logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        final Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logPath)) {
                try (var lines = Files.lines(logPath)) {
                    if (lines.anyMatch(line -> pattern.matcher(line).find())) {
                        return;
                    }
                } catch (final IOException ignored) {
                    // log may be mid-rotation; retry
                }
            }
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError(
                "Network '" + network.name() + "' never logged pattern /" + pattern + "/ within " + timeout);
    }

    /**
     * Scans the network's {@code hgcaa.log} once and fails if any line matches {@code pattern}.
     * Unlike {@link #awaitLogLine}, this asserts a <i>negative</i>, so call it only after the
     * behaviour that would have produced the line has already had its chance to run (e.g. after a
     * message has round-tripped).
     */
    static void assertLogLineAbsent(final SubProcessNetwork network, final Pattern pattern) {
        final Path logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        if (!Files.exists(logPath)) {
            throw new AssertionError("Log file 'hgcaa.log' not found.");
        }
        try (var lines = Files.lines(logPath)) {
            final var hit = lines.filter(line -> pattern.matcher(line).find()).findFirst();
            hit.ifPresent(line -> {
                throw new AssertionError(
                        "Network '" + network.name() + "' unexpectedly logged /" + pattern + "/: " + line);
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Full two-sided setup orchestrated as five phases:
     * <ol>
     *   <li>{@link #installLedgerConfig install} the local LedgerConfiguration on each network;</li>
     *   <li>{@link #captureConfigProof await} the first WRAPS-extensible recursive proof, then
     *       capture each network's config StateProof via {@code clprGetLedgerConfiguration};</li>
     *   <li>{@link #verifyProofOnPeer cross-verify} each captured proof on the PEER network — i.e.
     *       the same network that will run the real {@code clprCompleteChannel} verify path. Uses
     *       a consensus call to {@code 0x16e.verifyConfig}, so a failure here is the failure the
     *       peer would surface — catches stale or cross-network-incompatible proofs before they hit
     *       {@code deployAndConnect};</li>
     *   <li>{@link #deployAndConnect deploy} the {@link #VERIFIER} contract (pinned to the peer's
     *       WRAPS ledger id), the connector + system-contract caller, and run the channel +
     *       connector commit-reveal using the peer's captured config StateProof.</li>
     * </ol>
     */
    static Stream<DynamicTest> setupBothNetworks(
            final SubProcessNetwork ledgerA,
            final SubProcessNetwork ledgerB,
            final int portA,
            final int portB,
            final ClprCrypto crypto) {
        return setupBothNetworks(
                ledgerA, ledgerB, portA, portB, crypto, DEFAULT_MAX_MESSAGES_PER_BUNDLE, DEFAULT_MAX_QUEUE_DEPTH);
    }

    /**
     * Unique-suffix source for the {@code .via(...)} name given to each {@code verifyConfig}
     * freshness probe, so concurrently-running probes on the two ledgers don't collide on the
     * spec's transaction registry.
     */
    private static final AtomicLong PROBE_COUNTER = new AtomicLong();

    /**
     * Throttle-overrideable variant of {@link #setupBothNetworks(SubProcessNetwork,
     * SubProcessNetwork, int, int, ClprCrypto)}. Use when a test needs tight bundle-size
     * ({@code maxMessagesPerBundle}) or queue-depth ({@code maxQueueDepth}) limits — e.g. the
     * bundle-at-cap and queue-pressure regression tests.
     *
     * <p>Both ledgers are set up in parallel via {@link ParallelSpecOps}. For each ledger, a
     * sequential chain runs (per {@link #chainSetupAndConnect}):
     *
     * <ol>
     *   <li>install the local {@link ClprLedgerConfiguration};</li>
     *   <li>capture the resulting {@link com.hedera.hapi.block.stream.StateProof} into a
     *       per-chain {@link AtomicReference} sink;</li>
     *   <li>have the peer's {@code verifyConfig} accept that captured proof (cross-verify);</li>
     *   <li>deploy the local verifier contract and open the channel against the peer's
     *       captured proof.</li>
     * </ol>
     *
     * <p>The two chains synchronize at the deploy step via a {@link CountDownLatch}({@code 2})
     * — each chain counts down after step 2 so both proofs are guaranteed captured before
     * either chain enters step 4 (which reads the {@link AtomicReference} produced by the
     * other chain).
     *
     * <p>Runs {@code .failOnErrors()} so any step failure on either side surfaces as a test
     * failure rather than being logged-and-swallowed by {@code ParallelSpecOps}' default.
     *
     * <p>No proofs are cached across tests: each invocation reinstalls the {@code
     * ClprLedgerConfiguration} and re-runs the capture + cross-verify cycle. The
     * shared-multi-network listener (see {@code SharedMultiNetworkLauncherSessionListener})
     * already amortizes the expensive network-startup cost across the whole session.
     */
    static Stream<DynamicTest> setupBothNetworks(
            final SubProcessNetwork ledgerA,
            final SubProcessNetwork ledgerB,
            final int portA,
            final int portB,
            final ClprCrypto crypto,
            final int maxMessagesPerBundle,
            final int maxQueueDepth) {
        return setupBothNetworks(
                ledgerA,
                ledgerB,
                portA,
                portB,
                crypto,
                maxMessagesPerBundle,
                maxQueueDepth,
                DUMMY_TLS_CERT,
                DUMMY_TLS_CERT);
    }

    /**
     * As {@link #setupBothNetworks(SubProcessNetwork, SubProcessNetwork, int, int, ClprCrypto, int, int)}
     * but advertises {@code tlsCertA}/{@code tlsCertB} (the DER of each network's CLPR CA) as the
     * endpoint {@code tls_certificate} and, for mTLS, expects {@code portA}/{@code portB} to be each
     * network's {@code clpr.mtlsPort} so the channel is completed over — and syncs across — the
     * dedicated mutual-TLS listener rather than the plaintext path.
     */
    static Stream<DynamicTest> setupBothNetworks(
            final SubProcessNetwork ledgerA,
            final SubProcessNetwork ledgerB,
            final int portA,
            final int portB,
            final ClprCrypto crypto,
            final int maxMessagesPerBundle,
            final int maxQueueDepth,
            final byte[] tlsCertA,
            final byte[] tlsCertB) {
        final AtomicReference<ByteString> proofA = new AtomicReference<>();
        final AtomicReference<ByteString> proofB = new AtomicReference<>();
        // Fold install → capture → cross-verify → deployAndConnect per ledger into
        // ONE sequential chain, then run BOTH chains in parallel via ParallelSpecOps.
        // Cross-chain dependency at the deploy step: A's deployAndConnect uses proofB (produced
        // by B's chain) and vice versa. A CountDownLatch(2) ensures both proofs are captured
        // before either chain's deploy starts.
        //
        // .failOnErrors() so a bad step on either side surfaces as a test failure rather than
        // being logged-and-swallowed by ParallelSpecOps' default behavior.
        final var bothProofsReady = new CountDownLatch(2);
        final var chainA = chainSetupAndConnect(
                ledgerA,
                ledgerB,
                "hiero:298",
                portA,
                proofA,
                proofB,
                crypto,
                maxMessagesPerBundle,
                maxQueueDepth,
                tlsCertA,
                bothProofsReady);
        final var chainB = chainSetupAndConnect(
                ledgerB,
                ledgerA,
                "hiero:299",
                portB,
                proofB,
                proofA,
                crypto,
                maxMessagesPerBundle,
                maxQueueDepth,
                tlsCertB,
                bothProofsReady);
        return Stream.of(networkHapiTest(
                        "Install + capture + verify + deploy on both ledgers (parallel)",
                        ledgerA,
                        new ParallelSpecOps(chainA, chainB).failOnErrors())
                .findFirst()
                .orElseThrow());
    }

    /**
     * One ledger's full setup chain: install its own LedgerConfiguration, capture the resulting
     * StateProof into {@code selfProof}, have the peer verify it, then deploy the local verifier
     * contract and open the channel using the peer's proof.
     *
     * <p>Steps within this chain are strictly sequential — each depends on the prior's on-chain
     * effect or captured bytes. Two such chains run in parallel via {@link ParallelSpecOps}. The
     * {@code bothProofsReady} latch synchronizes the two chains at the deploy step: both must
     * finish capturing before either can call {@link #deployAndConnect} with the peer's proof.
     */
    private static SpecOperation chainSetupAndConnect(
            final SubProcessNetwork self,
            final SubProcessNetwork peer,
            final String selfChainId,
            final int selfPort,
            final AtomicReference<ByteString> selfProof,
            final AtomicReference<ByteString> peerProof,
            final ClprCrypto crypto,
            final int maxMessagesPerBundle,
            final int maxQueueDepth,
            final byte[] selfTlsCert,
            final CountDownLatch bothProofsReady) {
        return withOpContext((ignoredSpec, ignoredLog) -> {
            installLedgerConfig(self, selfChainId, selfPort, maxMessagesPerBundle, maxQueueDepth, selfTlsCert)
                    .getExecutable()
                    .execute();
            captureConfigProof(self, selfProof, maxMessagesPerBundle, maxQueueDepth)
                    .getExecutable()
                    .execute();
            verifyProofOnPeer(peer, selfProof).getExecutable().execute();
            // Signal our capture is done, then wait for the peer chain to reach the same point
            // before dispatching deployAndConnect (which uses the peer's proof).
            bothProofsReady.countDown();
            bothProofsReady.await();
            deployAndConnect(self, peer, crypto, peerProof).getExecutable().execute();
        });
    }

    /**
     * Wraps a {@link DynamicTest} (a HapiSpec built by e.g. {@link #deployAndConnect}) as a
     * {@link SpecOperation} so it can be a child of {@link ParallelSpecOps}. The wrapped op runs
     * on the calling thread — under {@code ParallelSpecOps} that's a distinct
     * {@link HapiSpec#getCommonThreadPool()} worker, so both children execute concurrently
     * against their respective target networks.
     */
    private static SpecOperation asSubOp(final DynamicTest dynamicTest) {
        return withOpContext(
                (ignoredSpec, ignoredLog) -> dynamicTest.getExecutable().execute());
    }

    /**
     * Phase 1: install the LedgerConfiguration in state. Funds the node operator account so it can
     * pay gas for the verifier-contract dispatch when handling inbound bundles. The {@code
     * ownPort} parameter is this network's gRPC port — peers read it from the StateProof-attested
     * config to know where to reach this network.
     */
    private static DynamicTest installLedgerConfig(
            final SubProcessNetwork network,
            final String ownChainId,
            final int ownPort,
            final int maxMessagesPerBundle,
            final int maxQueueDepth,
            final byte[] tlsCertificate) {
        return networkHapiTest(
                        "Install ledger config (" + ownChainId + ")",
                        network,
                        // Block until the latest BlockProvenSnapshot's ledger_id is populated, so
                        // ClprUpdateLedgerConfigurationHandler can read a non-empty
                        // stateProofManager.latestLedgerId() and set initial_trust_anchor on the
                        // config. Sequence:
                        //   1. WRAPS-extensible — proving state is loaded.
                        //   2. [CLPR-SYNC-POINT] — the FIRST block proof embeds the WRAPS recursive
                        //      proof. On warm-cache this is block #0; the snapshot for that block is
                        //      captured DURING its proof construction, which races with the WRAPS
                        //      embed → its ledger_id can still be empty.
                        //   3. POST_SYNC_POINT_SETTLE — give the system time to sign at least one
                        //      MORE block, whose snapshot is captured strictly after the WRAPS-
                        //      embed, so latestSnapshot().ledgerId() is guaranteed non-empty.
                        // Without this, captureConfigProof produces a StateProof with empty
                        // initial_trust_anchor and VerifyConfigCall rejects it on the peer.
                        withOpContext((spec, ignored) -> {
                            awaitWrapsExtensible(network);
                            awaitWrapsSyncPoint(network);
                            Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                        }),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                        clprUpdateLedgerConfiguration()
                                .configuration(buildLedgerConfig(
                                        ownChainId, ownPort, maxMessagesPerBundle, maxQueueDepth, tlsCertificate))
                                .payingWith(GENESIS))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Phase 2: block (via log polling) until the network has produced its first WRAPS-extensible
     * recursive proof, optionally settle so the WRAPS proof state finishes baking, then poll
     * {@code clprGetLedgerConfiguration} until it returns a non-empty StateProof.
     *
     * <p>The cold-path settle is the key difference from a warm-cache run: the runtime
     * "WRAPS-extensible? true" log fires the moment the FIRST extensible proof exists, but a
     * proof captured immediately after that may not yet carry the key material a PEER network
     * needs to verify it. Settling lets several more blocks fire so the recursive WRAPS proof
     * binds the necessary material into the captured snapshot. Warm path already loads a
     * fully-baked fixture so no settle is needed there.
     */
    private static DynamicTest captureConfigProof(
            final SubProcessNetwork network,
            final AtomicReference<ByteString> sink,
            final int expectedMaxMessagesPerBundle,
            final int expectedMaxQueueDepth) {
        return networkHapiTest("Capture ledger-config StateProof", network, withOpContext((spec, ignored) -> {
                    final var readiness = awaitWrapsExtensible(network);
                    if (readiness == WrapsReadiness.COLD_CONSTRUCTED
                            && !MultiNetworkExtension.wasTssBootstrapHandled(network)) {
                        // Cold path AND the extension didn't already pay the wait on our
                        // behalf — poll for the [CLPR-SYNC-POINT] log emitted by
                        // BlockStreamManagerImpl when the first block proof embeds the
                        // WRAPS recursive proof, then briefly settle so a few WRAPS-
                        // carrying blocks accumulate before capture. Skipped when
                        // tssPreload=true cold first-run (extension paid it in
                        // startNetworks) and when warm (preload fixture is mature).
                        awaitWrapsSyncPoint(network);
                        Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                    }
                    // Pre-register the CLPR system contract so the local probe-verify
                    // (freshness gate) can target it by name via contractCall.
                    spec.registry()
                            .saveContractId(
                                    VERIFIER + "_PROBE",
                                    ContractID.newBuilder()
                                            .setShardNum(spec.shard())
                                            .setRealmNum(spec.realm())
                                            .setContractNum(CLPR_SYSTEM_CONTRACT_NUM)
                                            .build());
                    final var deadline = Instant.now().plus(Duration.ofMinutes(2));
                    while (Instant.now().isBefore(deadline)) {
                        sink.set(ByteString.EMPTY);
                        allRunFor(
                                spec,
                                clprGetLedgerConfiguration().payingWith(GENESIS).exposingProofTo(sink::set));
                        final var captured = sink.get();
                        // Freshness gate: re-runs verifyConfig(bytes) on the captured proof and
                        // checks the throttles ENCODED IN THE PROOF — not the live query response.
                        // clprGetLedgerConfiguration's StateProof references a SIGNED block which
                        // can lag the latest committed state by several seconds. Without this
                        // check, the gate would accept a proof from BEFORE the just-issued install
                        // committed (initialTrustAnchor was already non-empty from any prior
                        // install on the shared network). That stale proof would later be
                        // verified at clprCompleteChannel and the channel's peerThrottles
                        // would carry the PRIOR install's values, mispacing the orchestrator.
                        if (captured != null
                                && !captured.isEmpty()
                                && proofMatchesExpectedThrottles(
                                        spec,
                                        captured.toByteArray(),
                                        expectedMaxMessagesPerBundle,
                                        expectedMaxQueueDepth)) {
                            return;
                        }
                        Thread.sleep(POLL_INTERVAL.toMillis());
                    }
                    throw new IllegalStateException("Network '" + network.name() + "' never produced a locally "
                            + "verifiable config state proof carrying the just-installed throttles "
                            + "(expectedMaxMessagesPerBundle=" + expectedMaxMessagesPerBundle
                            + ", expectedMaxQueueDepth=" + expectedMaxQueueDepth + ")");
                }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Legacy V1 ABI for {@code verifyConfig(bytes proofBytes) returns (bytes)} on the CLPR
     * system contract precompile. Matches the default {@code clpr.endpointManifestEnabled=false}
     * runtime dispatch. The precompile has no Solidity-generated .json on disk; we hand-craft
     * the single-function ABI for {@code Function.fromJson}.
     *
     * <p>Used by the freshness probe, which only inspects the decoded
     * {@code ClprLedgerConfiguration} — no manifest info needed.
     */
    private static final String VERIFY_CONFIG_ABI = "{\"name\":\"verifyConfig\","
            + "\"inputs\":[{\"name\":\"proofBytes\",\"type\":\"bytes\"}],"
            + "\"outputs\":[{\"name\":\"\",\"type\":\"bytes\"}],"
            + "\"stateMutability\":\"view\",\"type\":\"function\"}";

    /**
     * Manifest-aware V2 ABI for
     * {@code verifyConfig(bytes proofBytes, bytes channelId, bytes manifestProofBytes)
     * returns (bytes configBytes, bytes manifestBytes)}. Matches the runtime dispatch when
     * {@code clpr.endpointManifestEnabled=true}. Not used by the current freshness probe —
     * kept here so future manifest-aware tests can invoke the V2 selector by swapping the
     * ABI constant on the individual call site.
     */
    @SuppressWarnings("unused")
    private static final String VERIFY_CONFIG_V2_ABI = "{\"name\":\"verifyConfig\","
            + "\"inputs\":[{\"name\":\"proofBytes\",\"type\":\"bytes\"},"
            + "{\"name\":\"channelId\",\"type\":\"bytes\"},"
            + "{\"name\":\"manifestProofBytes\",\"type\":\"bytes\"}],"
            + "\"outputs\":[{\"name\":\"configBytes\",\"type\":\"bytes\"},"
            + "{\"name\":\"manifestBytes\",\"type\":\"bytes\"}],"
            + "\"stateMutability\":\"view\",\"type\":\"function\"}";

    /**
     * Phase 3: cross-network probe. Runs {@code verifyConfig(bytes)} on the PEER network — the
     * same {@code EvmClprVerifier} code path {@code clprCompleteChannel} will exercise — so
     * a SUCCESS here means the peer will accept the source's proof. Required because a proof
     * that self-verifies on the source ledger may still be rejected on a peer that doesn't yet
     * share the source's TSS key material (notably on cold path, where the WRAPS recursive
     * proof needs settle time to embed the keys the peer needs).
     *
     * <p>Retries on non-SUCCESS replies until either acceptance or timeout; warm path typically
     * succeeds on the first attempt. {@code .hasKnownStatus(SUCCESS)} throws on non-SUCCESS,
     * which we catch and convert to "peer hasn't accepted yet — retry."
     */
    private static DynamicTest verifyProofOnPeer(
            final SubProcessNetwork peer, final AtomicReference<ByteString> sourceProofSink) {
        return networkHapiTest("Verify peer accepts our config StateProof", peer, withOpContext((peerSpec, ignored) -> {
                    peerSpec.registry()
                            .saveContractId(
                                    VERIFIER + "_PROBE",
                                    ContractID.newBuilder()
                                            .setShardNum(peerSpec.shard())
                                            .setRealmNum(peerSpec.realm())
                                            .setContractNum(CLPR_SYSTEM_CONTRACT_NUM)
                                            .build());
                    final var deadline = Instant.now().plus(Duration.ofMinutes(3));
                    while (Instant.now().isBefore(deadline)) {
                        final var captured = sourceProofSink.get();
                        if (captured != null
                                && !captured.isEmpty()
                                && probeProofVerifies(peerSpec, captured.toByteArray())) {
                            return;
                        }
                        Thread.sleep(POLL_INTERVAL.toMillis());
                    }
                    throw new IllegalStateException("Peer '" + peer.name() + "' never accepted the source's captured "
                            + "config state proof within probe deadline");
                }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Probes whether the given network's CLPR system contract precompile will accept the
     * captured StateProof via {@code verifyConfig(bytes)}. Issues a CONSENSUS contract call
     * (not a local static call) — same code path {@code clprCompleteChannel}'s
     * {@code EvmClprVerifier} dispatch runs, so by construction it returns the same
     * accept/reject decision the peer would.
     */
    private static boolean probeProofVerifies(final HapiSpec spec, final byte[] capturedProof) {
        // Accept SUCCESS or CONTRACT_REVERT_EXECUTED as permissible outcomes so the polling loop
        // doesn't throw (and log ERROR) on every attempt while the proof is still stale. The
        // caller distinguishes accept vs retry by inspecting the actual status below.
        final var op = contractCallWithFunctionAbi(VERIFIER + "_PROBE", VERIFY_CONFIG_ABI, (Object) capturedProof)
                .payingWith(GENESIS)
                .gas(GAS)
                .hasKnownStatusFrom(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                .noLogging();
        allRunFor(spec, op);
        return op.getActualStatus() == ResponseCodeEnum.SUCCESS;
    }

    /**
     * Stronger freshness gate: runs {@code verifyConfig(bytes)} on the proof and inspects the
     * decoded {@link ClprLedgerConfiguration} the precompile recovers from inside the proof.
     * If the decoded throttles don't match what we just installed, the proof references a block
     * signed BEFORE our install committed — caller re-polls. {@link #probeProofVerifies}'s
     * SUCCESS check alone catches the "no install ever ran" case (empty initialTrustAnchor) but
     * passes any proof whose initialTrustAnchor was set by a PRIOR install, masking stale-proof
     * leakage across tests on a shared network.
     */
    private static boolean proofMatchesExpectedThrottles(
            final HapiSpec spec,
            final byte[] capturedProof,
            final int expectedMaxMessagesPerBundle,
            final int expectedMaxQueueDepth) {
        // Accept SUCCESS or CONTRACT_REVERT_EXECUTED as permissible outcomes so the polling loop
        // doesn't throw (and log ERROR) on every attempt while the proof is still stale. Don't
        // wire exposingResultTo — that would make the framework auto-decode the return value
        // even on REVERT (via HapiContractCall.updateStateOf → doObservedLookup), and the
        // reverted call's empty return bytes trip a BufferUnderflowException inside headlong's
        // Function.decodeReturn. Instead: name the txn via .via(...), then when we see SUCCESS,
        // fetch the record and decode ourselves in a controlled try-block.
        final var probeTxn = "clprProofFreshnessProbe_" + PROBE_COUNTER.incrementAndGet();
        final var op = contractCallWithFunctionAbi(VERIFIER + "_PROBE", VERIFY_CONFIG_ABI, (Object) capturedProof)
                .payingWith(GENESIS)
                .gas(GAS)
                .via(probeTxn)
                .hasKnownStatusFrom(ResponseCodeEnum.SUCCESS, ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                .noLogging();
        allRunFor(spec, op);
        if (op.getActualStatus() != ResponseCodeEnum.SUCCESS) {
            return false;
        }
        final var decoded = new AtomicReference<byte[]>();
        allRunFor(spec, getTxnRecord(probeTxn).assertingNothing().noLogging().exposingTo(record -> {
            try {
                final var callResult =
                        record.getContractCallResult().getContractCallResult().toByteArray();
                final var result = Function.fromJson(VERIFY_CONFIG_ABI).decodeReturn(callResult);
                if (result.size() >= 1 && result.get(0) instanceof byte[] b) {
                    decoded.set(b);
                }
            } catch (final Exception e) {
                log.warn("[HieroToHieroBase] proofMatchesExpectedThrottles: verifyConfig result decode failed", e);
            }
        }));
        final byte[] configBytes = decoded.get();
        if (configBytes == null || configBytes.length == 0) {
            return false;
        }
        try {
            final var parsed = ClprLedgerConfiguration.parseFrom(configBytes);
            if (!parsed.hasThrottles()) {
                return false;
            }
            final var t = parsed.getThrottles();
            return t.getMaxMessagesPerBundle() == expectedMaxMessagesPerBundle
                    && t.getMaxQueueDepth() == expectedMaxQueueDepth;
        } catch (final Exception e) {
            log.warn("[HieroToHieroBase] proofMatchesExpectedThrottles: ClprLedgerConfiguration parse failed", e);
            return false;
        }
    }

    /**
     * Phase 3: deploy the verifier (pinned to the peer's WRAPS ledger id), the connector contract,
     * and the system-contract caller; fund the connector; then run the channel + connector
     * commit-reveal. The peer's config StateProof is read at execution time via {@link
     * com.hedera.services.bdd.spec.utilops.UtilVerbs#sourcing sourcing} so phase 2 has populated
     * the reference before this networkHapiTest builds its clprCompleteChannel op.
     *
     * <p>All contractCreate + clprXxx ops live in a single {@code networkHapiTest} so contract IDs
     * (PassThroughAuth, ClprSystemContract, ClprLedgerVerifier) remain visible to the later
     * commit-reveal verbs in the same HapiSpec registry.
     */
    private static DynamicTest deployAndConnect(
            final SubProcessNetwork network,
            final SubProcessNetwork peerNetwork,
            final ClprCrypto crypto,
            final AtomicReference<ByteString> peerConfigProof) {
        return networkHapiTest(
                        "Deploy verifier + open channel to " + peerNetwork.name(),
                        network,
                        // Pre-register the CLPR system contract (0x16e) into THIS spec's registry
                        // so verifierContract(VERIFIER) resolves to it. spec.shard()/realm() pick
                        // up the subprocess's coordinates (11.12 in CI, 0.0 locally) at runtime.
                        withOpContext((spec, ignoredLog) -> spec.registry()
                                .saveContractId(
                                        VERIFIER,
                                        ContractID.newBuilder()
                                                .setShardNum(spec.shard())
                                                .setRealmNum(spec.realm())
                                                .setContractNum(CLPR_SYSTEM_CONTRACT_NUM)
                                                .build())),
                        uploadInitCode(CONNECTOR_CONTRACT),
                        contractCreate(CONNECTOR_CONTRACT),
                        // Fund the connector contract so it has enough balance to cover the
                        // ClprSubmitBundleHandler worst-case per-message charge.
                        cryptoTransfer(tinyBarsFromTo(GENESIS, CONNECTOR_CONTRACT, ONE_HUNDRED_HBARS)),
                        uploadInitCode(CLPR_CONTRACT),
                        contractCreate(CLPR_CONTRACT),
                        // Channel commit-reveal
                        clprRegisterChannel()
                                .ownershipCommitment(crypto.channelCommitment)
                                .payingWith(GENESIS),
                        sourcing(() -> clprCompleteChannel()
                                .channelId(crypto.channelId)
                                .publicKey(crypto.publicKey)
                                .signature(crypto.channelSignature)
                                .signatureScheme(ClprSignatureScheme.ED25519)
                                .verifierContract(VERIFIER)
                                .configProofBytes(peerConfigProof.get().toByteArray())
                                .payingWith(GENESIS)
                                // Fail loudly if the captured StateProof was stale — would otherwise
                                // pass silently and downstream knownChannels would stay at 0.
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS)),
                        // Connector commit-reveal
                        clprRegisterConnector()
                                .commitment(crypto.connectorCommitment)
                                .payingWith(GENESIS),
                        clprCompleteConnector()
                                .connectorId(crypto.connectorId)
                                .publicKey(crypto.publicKey)
                                .signature(crypto.connectorSignature)
                                .signatureScheme(ClprSignatureScheme.ED25519)
                                .salt(crypto.connectorSalt)
                                .channelId(crypto.channelId)
                                .connectorContract(CONNECTOR_CONTRACT)
                                .adminKeyName(GENESIS)
                                .lockedStake(MIN_STAKE)
                                .payingWith(GENESIS))
                .findFirst()
                .orElseThrow();
    }
    // ── Polling helpers ───────────────────────────────────────────────────────

    static DynamicTest awaitReceivedMessage(
            final SubProcessNetwork network, final byte[] channelId, final long minCount) {
        return networkHapiTest(
                        "Await received >= " + minCount + " message(s)",
                        network,
                        uploadInitCode(CLPR_CONTRACT),
                        contractCreate(CLPR_CONTRACT),
                        withOpContext((spec, ignored) -> {
                            final var deadline = Instant.now().plus(DELIVERY_TIMEOUT);
                            final var queueStateAbi = getABIFor(FUNCTION, GET_QUEUE_STATE, CLPR_CONTRACT);
                            while (Instant.now().isBefore(deadline)) {
                                final long[] counts = {0L, 0L};
                                try {
                                    allRunFor(
                                            spec,
                                            QueryVerbs.contractCallLocalWithFunctionAbi(
                                                            CLPR_CONTRACT, queueStateAbi, (Object) channelId)
                                                    .exposingTypedResultsTo(results -> {
                                                        if (results.length >= 2) {
                                                            counts[0] = ((BigInteger) results[0]).longValue();
                                                            counts[1] = ((BigInteger) results[1]).longValue();
                                                        }
                                                    }));
                                    if (counts[0] >= minCount) return;
                                } catch (final Exception e) {
                                    // channel may not be ready yet; retry
                                }
                                Thread.sleep(POLL_INTERVAL.toMillis());
                            }
                            // Final assertion — let it fail with context
                            final long[] finalCounts = {0L, 0L};
                            try {
                                allRunFor(
                                        spec,
                                        QueryVerbs.contractCallLocalWithFunctionAbi(
                                                        CLPR_CONTRACT, queueStateAbi, (Object) channelId)
                                                .exposingTypedResultsTo(results -> {
                                                    if (results.length >= 2) {
                                                        finalCounts[0] = ((BigInteger) results[0]).longValue();
                                                    }
                                                }));
                            } catch (final Exception e) {
                                // ignored
                            }
                            assertTrue(
                                    finalCounts[0] >= minCount,
                                    "Expected receivedMessageId >= " + minCount + " on " + network.name() + " after "
                                            + DELIVERY_TIMEOUT + ", got " + finalCounts[0]);
                        }))
                .findFirst()
                .orElseThrow();
    }

    static DynamicTest awaitAckedMessage(final SubProcessNetwork network, final byte[] channelId, final long minCount) {
        return networkHapiTest(
                        "Await acked >= " + minCount + " message(s)",
                        network,
                        uploadInitCode(CLPR_CONTRACT),
                        contractCreate(CLPR_CONTRACT),
                        withOpContext((spec, ignoredLog) -> {
                            final var deadline = Instant.now().plus(DELIVERY_TIMEOUT);
                            final var queueStateAbi = getABIFor(FUNCTION, GET_QUEUE_STATE, CLPR_CONTRACT);
                            while (Instant.now().isBefore(deadline)) {
                                final long[] counts = {0L, 0L};
                                try {
                                    allRunFor(
                                            spec,
                                            QueryVerbs.contractCallLocalWithFunctionAbi(
                                                            CLPR_CONTRACT, queueStateAbi, (Object) channelId)
                                                    .exposingTypedResultsTo(results -> {
                                                        if (results.length >= 2) {
                                                            counts[0] = ((BigInteger) results[0]).longValue();
                                                            counts[1] = ((BigInteger) results[1]).longValue();
                                                        }
                                                    }));
                                    if (counts[1] >= minCount) return;
                                } catch (final Exception e) {
                                    // not ready yet; retry
                                }
                                Thread.sleep(POLL_INTERVAL.toMillis());
                            }
                            final long[] finalCounts = {0L, 0L};
                            try {
                                allRunFor(
                                        spec,
                                        QueryVerbs.contractCallLocalWithFunctionAbi(
                                                        CLPR_CONTRACT, queueStateAbi, (Object) channelId)
                                                .exposingTypedResultsTo(results -> {
                                                    if (results.length >= 2) {
                                                        finalCounts[1] = ((BigInteger) results[1]).longValue();
                                                    }
                                                }));
                            } catch (final Exception e) {
                                // ignored
                            }
                            assertTrue(
                                    finalCounts[1] >= minCount,
                                    "Expected ackedMessageId >= " + minCount + " on " + network.name() + " after "
                                            + DELIVERY_TIMEOUT + ", got " + finalCounts[1]);
                        }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Polls a single network's channel by probing {@code sendMessage}; returns once the call
     * is rejected (a behavioral signal that the channel is no longer {@code ACTIVE}). Probes
     * have no side effect on the close path: if the channel is still ACTIVE the probe enqueues
     * one message that is dispatched normally and receives a real response, so drain still completes.
     */
    static DynamicTest awaitChannelNonActive(final SubProcessNetwork network, final ClprCrypto crypto) {
        return networkHapiTest(
                        "Await channel non-ACTIVE",
                        network,
                        cryptoCreate("probeCaller").balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CLPR_CONTRACT),
                        contractCreate(CLPR_CONTRACT),
                        withOpContext((spec, opLog) -> {
                            final var deadline = Instant.now().plus(DELIVERY_TIMEOUT);
                            while (Instant.now().isBefore(deadline)) {
                                try {
                                    allRunFor(
                                            spec,
                                            contractCall(
                                                            CLPR_CONTRACT,
                                                            SEND_MESSAGE,
                                                            crypto.channelId,
                                                            crypto.connectorId,
                                                            new byte[20],
                                                            "probe".getBytes(StandardCharsets.UTF_8))
                                                    .gas(GAS)
                                                    .payingWith("probeCaller")
                                                    .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED));
                                    return;
                                } catch (final Exception e) {
                                    // Probe either succeeded (channel still ACTIVE) or got a
                                    // different rejection code; retry until deadline.
                                }
                                Thread.sleep(POLL_INTERVAL.toMillis());
                            }
                            assertTrue(
                                    false,
                                    "Channel on " + network.name() + " did not move out of ACTIVE within "
                                            + DELIVERY_TIMEOUT);
                        }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Polls {@code clpr.sendMessage} on a probe caller for {@code window} and asserts the call
     * always succeeds (ie. the channel never leaves ACTIVE). Inverse of
     * {@link #awaitChannelNonActive}. Used to catch transient PAUSE during multi-message
     * traffic — if any probe in the window reverts (eg. with
     * {@code CLPR_INVALID_CHANNEL_STATUS}), the test fails. Probe payload bytes are sent on
     * the channel (counts against receivedMessageId on the peer), so tests that also assert
     * exact ack counts should account for the probe stream or skip this gate.
     */
    static DynamicTest assertChannelStaysActive(
            final SubProcessNetwork network, final ClprCrypto crypto, final Duration window) {
        return networkHapiTest(
                        "Assert channel stays ACTIVE for " + window,
                        network,
                        cryptoCreate("activeProbeCaller").balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CLPR_CONTRACT),
                        contractCreate(CLPR_CONTRACT),
                        withOpContext((spec, opLog) -> {
                            final var deadline = Instant.now().plus(window);
                            while (Instant.now().isBefore(deadline)) {
                                // Status-only probe via the precompile. We need this NOT to revert
                                // with the "channel not ACTIVE" code; any SUCCESS / other-code
                                // outcome is acceptable from the gate's perspective.
                                allRunFor(
                                        spec,
                                        contractCall(
                                                        CLPR_CONTRACT,
                                                        SEND_MESSAGE,
                                                        crypto.channelId,
                                                        crypto.connectorId,
                                                        new byte[20],
                                                        "active-probe".getBytes(StandardCharsets.UTF_8))
                                                .gas(GAS)
                                                .payingWith("activeProbeCaller")
                                                .hasKnownStatus(ResponseCodeEnum.SUCCESS));
                                Thread.sleep(POLL_INTERVAL.toMillis());
                            }
                        }))
                .findFirst()
                .orElseThrow();
    }

    // ── sendMessage helpers ───────────────────────────────────────────────────

    /** Single {@code precompile.sendMessage} call with default empty target and {@link #GAS}. */
    static HapiContractCall sendOp(final ClprCrypto crypto, final String caller, final String data) {
        return contractCall(
                        CLPR_CONTRACT,
                        SEND_MESSAGE,
                        crypto.channelId,
                        crypto.connectorId,
                        new byte[20],
                        data.getBytes(StandardCharsets.UTF_8))
                .gas(GAS)
                .payingWith(caller);
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    /**
     * Placeholder {@code tls_certificate} advertised by the plaintext (non-mTLS) suites. It cannot be
     * empty: {@code ClprUpdateLedgerConfigurationHandler.validateEndpoint} rejects an endpoint whose
     * {@code tls_certificate} equals {@code Bytes.EMPTY} with {@code CLPR_INVALID_SEED_ENDPOINT}. These
     * bytes are never parsed on the plaintext path (only the mTLS server/synchronizer read the cert),
     * so a single inert byte is enough.
     */
    static final byte[] DUMMY_TLS_CERT = {0x01};

    static ClprLedgerConfiguration buildLedgerConfig(
            final String chainId, final int peerPort, final int maxMessagesPerBundle, final int maxQueueDepth) {
        return buildLedgerConfig(chainId, peerPort, maxMessagesPerBundle, maxQueueDepth, DUMMY_TLS_CERT);
    }

    /**
     * As {@link #buildLedgerConfig(String, int, int, int)} but advertises {@code tlsCertificate} as
     * the endpoint's {@code tls_certificate} (the DER of this network's CLPR CA). For mTLS suites,
     * {@code peerPort} must be this network's {@code clpr.mtlsPort} — the port the dedicated mTLS
     * sync listener binds — since with mTLS on, the CLPR {@code sync} method is served only there.
     */
    static ClprLedgerConfiguration buildLedgerConfig(
            final String chainId,
            final int peerPort,
            final int maxMessagesPerBundle,
            final int maxQueueDepth,
            final byte[] tlsCertificate) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(chainId)
                .setServiceAddress(ByteString.copyFrom(new byte[] {0, 0, 1}))
                .addEndpoints(ClprEndpoint.newBuilder()
                        .setServiceEndpoint(ClprServiceEndpoint.newBuilder()
                                .setIpAddress("127.0.0.1")
                                .setPort(peerPort)
                                .build())
                        .setTlsCertificate(ByteString.copyFrom(tlsCertificate))
                        .build())
                .setThrottles(ClprThrottles.newBuilder()
                        .setMaxMessagesPerBundle(maxMessagesPerBundle)
                        .setMaxMessagePayloadBytes(65536)
                        .setMaxGasPerMessage(1_000_000L)
                        .setMaxQueueDepth(maxQueueDepth)
                        .setMaxSyncBytes(1_048_576L)
                        .build())
                .build();
    }

    // ── Crypto ────────────────────────────────────────────────────────────────

    static final class ClprCrypto {
        // Fixed test private key — deterministic across runs
        private static final byte[] TEST_PRIVATE_KEY = {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
        };
        // 0x000000000000000000000000000000000000016e (CLPR system contract)
        private static final byte[] CLPR_SERVICE_ADDRESS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e
        };
        // Channel state from a prior test in the same shared-network
        // run lingers in ledger state, so each ClprCrypto must derive a distinct channelId
        // (and connectorId) to avoid CLPR_CHANNEL_ALREADY_EXISTS on re-registration.
        private static final AtomicLong INSTANCE_COUNTER = new AtomicLong();

        final byte[] publicKey = new byte[32];
        final byte[] channelId;
        final byte[] channelCommitment;
        final byte[] channelSignature = new byte[Ed25519.SIGNATURE_SIZE];
        final byte[] connectorSalt = new byte[32]; // all zeros
        final byte[] connectorId;
        final byte[] connectorCommitment;
        final byte[] connectorSignature = new byte[Ed25519.SIGNATURE_SIZE];

        ClprCrypto() {
            Ed25519.generatePublicKey(TEST_PRIVATE_KEY, 0, publicKey, 0);

            final var seed = "hiero-e2e-test-conn-v1-" + INSTANCE_COUNTER.incrementAndGet();
            channelId = keccak256(seed.getBytes(StandardCharsets.UTF_8));

            // Channel commitment: keccak256(channelId || pubKey)
            channelCommitment = keccak256(concat(channelId, publicKey));

            // Channel signature: Ed25519 over keccak256(channelId)
            final var connSigMsg = keccak256(channelId);
            Ed25519.sign(TEST_PRIVATE_KEY, 0, connSigMsg, 0, connSigMsg.length, channelSignature, 0);

            // connectorId = keccak256(channelId || pubKey || connectorSalt)
            connectorId = keccak256(concat(channelId, concat(publicKey, connectorSalt)));

            // Connector commitment: keccak256(connectorId || pubKey)
            connectorCommitment = keccak256(concat(connectorId, publicKey));

            // Connector signature: Ed25519 over keccak256(connectorId || CLPR_SERVICE_ADDRESS)
            final var connectorSigMsg = keccak256(concat(connectorId, CLPR_SERVICE_ADDRESS));
            Ed25519.sign(TEST_PRIVATE_KEY, 0, connectorSigMsg, 0, connectorSigMsg.length, connectorSignature, 0);
        }

        private static byte[] keccak256(final byte[] input) {
            return new Keccak.Digest256().digest(input);
        }

        private static byte[] concat(final byte[] a, final byte[] b) {
            final byte[] r = new byte[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }
}
