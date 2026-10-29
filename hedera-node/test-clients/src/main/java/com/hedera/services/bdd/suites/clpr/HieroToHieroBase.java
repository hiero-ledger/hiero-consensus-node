// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr;

import static com.hedera.node.app.info.DiskStartupNetworks.OVERRIDE_NETWORK_JSON;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.allNodes;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.classicFeeCollectorIdFor;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CANDIDATE_ROSTER_JSON;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetEndpointManifest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.clprGetLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprCompleteConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterChannel;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprRegisterConnector;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprSubmitBundle;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.clprUpdateLedgerConfiguration;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeUpgrade;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActive;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextStakingPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.upgrade.BuildUpgradeZipOp.FAKE_UPGRADE_ZIP_LOC;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.DEFAULT_UPGRADE_FILE_ID;
import static com.hedera.services.bdd.suites.freeze.CommonUpgradeResources.upgradeFileHashAt;
import static com.hedera.services.bdd.suites.regression.system.LifecycleTest.confirmFreezeAndShutdown;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.internal.network.Network;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hedera.services.bdd.spec.utilops.grouping.ParallelSpecOps;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import com.hederahashgraph.api.proto.java.ClprEndpointManifest;
import com.hederahashgraph.api.proto.java.ClprLedgerConfiguration;
import com.hederahashgraph.api.proto.java.ClprSignatureScheme;
import com.hederahashgraph.api.proto.java.ClprThrottles;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Ed25519;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;
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
public abstract class HieroToHieroBase implements LifecycleTest {

    /** How long to poll {@code clprGetEndpointManifest} before failing a manifest assertion. */
    static final Duration MANIFEST_APPEAR_TIMEOUT = Duration.ofMinutes(2);

    /**
     * The lowest manifest version that counts as "finalized": genesis seeds version 1, so a
     * version {@literal >=} 2 proves the reconciler has rebuilt the manifest at least once.
     */
    public static final long FINALIZED_MANIFEST_MIN_VERSION = 2L;

    private static final Logger log = LogManager.getLogger(HieroToHieroBase.class);

    // Registry name under which the CLPR system contract precompile (0x16e) is pre-registered
    // and used as the verifier contract — the Java-side dispatch already targets the precompile's
    // own verifyConfig(bytes,bytes32) / verifyBundle(bytes,bytes,bytes) registered methods, so a Solidity proxy
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
     * Since the WRAPS library computes a proof in seconds, a single-node subprocess network should
     * finish the first extensible construction within a few minutes of startup; 10 minutes leaves slack.
     */
    static final Duration WRAPS_EXTENSIBLE_TIMEOUT = Duration.ofMinutes(10);

    static final Pattern WRAPS_EXTENSIBLE_PATTERN =
            Pattern.compile("History proof constructed \\(#\\d+, WRAPS-extensible\\? true\\)");

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

    /**
     * Upper bound on how long one ledger's setup chain waits at the deploy barrier for the other's captures: the
     * peer's WRAPS readiness and sync-point waits plus its config- and manifest-proof polls, with headroom. A peer
     * whose capture throws still releases the barrier, so this only bounds a peer that is itself stuck.
     */
    static final Duration BOTH_PROOFS_READY_TIMEOUT = WRAPS_EXTENSIBLE_TIMEOUT
            .plus(WRAPS_SYNC_POINT_TIMEOUT.multipliedBy(2))
            .plus(MANIFEST_APPEAR_TIMEOUT.multipliedBy(2))
            .plus(Duration.ofMinutes(5));

    /**
     * Stake added to each node already in the roster when new nodes are enrolled into the hinTS scheme, so the
     * nodes already signing keep more than 2/3 of every rotation's weight.
     */
    static final long EXISTING_NODE_STAKE = 10 * ONE_HBAR;
    /**
     * Stake added to each new node per enrollment rotation. Small next to {@link #EXISTING_NODE_STAKE}, so even
     * after {@link #MAX_ENROLLMENT_ROTATIONS} rotations the new nodes stay well below 1/3 of the weight.
     */
    static final long ADDED_NODE_STAKE = ONE_HBAR;
    /** Most weight rotations to run before every new node has a key in the active hinTS scheme. */
    static final int MAX_ENROLLMENT_ROTATIONS = 3;
    /** How long to wait for one enrollment rotation's hinTS scheme and history proof. */
    static final Duration ROTATION_TSS_TIMEOUT = Duration.ofMinutes(5);
    /** Balance given to each added node's account, which pays for the bundles that node submits. */
    static final long ADDED_NODE_ACCOUNT_FUNDING = 100_000_000_000L;
    /** How long an upgrade that adds a node waits for every node to be ACTIVE (a new node first reconnects). */
    static final Duration ROSTER_CHANGE_TO_ACTIVE_TIMEOUT = Duration.ofMinutes(5);
    /** How long to wait for a node to export the candidate roster after {@code PREPARE_UPGRADE}. */
    static final Duration CANDIDATE_ROSTER_EXPORT_TIMEOUT = Duration.ofMinutes(1);
    /** How long to wait for the candidate roster's history construction and its WRAPS proof. */
    static final Duration CANDIDATE_ROSTER_TSS_TIMEOUT = Duration.ofMinutes(10);

    /**
     * The config version each ledger last started with, by network name (0, the genesis version, if never
     * upgraded). {@link #CURRENT_CONFIG_VERSION} is shared by all networks, so it says nothing about one ledger's.
     */
    private static final Map<String, Integer> CONFIG_VERSIONS = new ConcurrentHashMap<>();

    /** Makes staker account names unique across a test's rotations. */
    private static final AtomicLong STAKER_SEQ = new AtomicLong();

    /** Poll interval for {@link #awaitPortsFree}. */
    private static final Duration PORT_FREE_POLL = Duration.ofSeconds(5);
    /** Cap for {@link #awaitPortsFree} before giving up (a killed node should release ports in seconds). */
    private static final Duration PORT_FREE_TIMEOUT = Duration.ofSeconds(180);

    // ── Shared setup helper ───────────────────────────────────────────────────

    /**
     * Blocks until this network is ready to produce signatures that cross-network bundle
     * verification will accept. Returns when BOTH conditions hold:
     *
     * <ol>
     *   <li>The history proof construction is WRAPS-extensible — freshly built at runtime
     *       ({@code History proof constructed (#N, WRAPS-extensible? true)} log line); AND</li>
     *   <li>The first block proof embeds the WRAPS recursive material (the one-shot
     *       {@code [CLPR-SYNC-POINT]} log emitted by {@code BlockStreamManagerImpl}), which
     *       fires shortly after the runtime construction event.</li>
     * </ol>
     *
     * <p>Latency: a few minutes (waits for runtime WRAPS construction).
     */
    public static void awaitWrapsExtensible(final SubProcessNetwork network) {
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
                    // Match the runtime-constructed WRAPS-extensible proof (implies blocks have been signed).
                    final boolean matched = lines.anyMatch(
                            line -> WRAPS_EXTENSIBLE_PATTERN.matcher(line).find());
                    if (matched) {
                        // Ensure the first WRAPS-carrying block proof has materialized before
                        // returning — [CLPR-SYNC-POINT] is the first signal that a real block
                        // proof has been produced with WRAPS material attached.
                        awaitWrapsSyncPoint(network);
                        return;
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
     * <p>The runtime {@code History proof constructed (..., WRAPS-extensible? true)} log fires once
     * the history+TSS state is ready, but it can take an additional block or two before that material
     * actually shows up inside a block proof. {@code [CLPR-SYNC-POINT]} is the deterministic moment
     * cross-network state proofs become peer-verifiable.
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
     *
     * @return the match in the first matching line
     */
    public static MatchResult awaitLogLine(
            final SubProcessNetwork network, final Pattern pattern, final Duration timeout) {
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
                    final var match =
                            lines.map(pattern::matcher).filter(Matcher::find).findFirst();
                    if (match.isPresent()) {
                        return match.get().toMatchResult();
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

    // ---- endpoint-manifest helpers (shared by the manifest suites) ----

    /**
     * Polls {@code clprGetEndpointManifest} on {@code network} until the manifest is finalized
     * (version {@literal >=} 2 with {@literal >=} 1 endpoints), then records that version into
     * {@code sink} for later relative assertions.
     */
    static DynamicTest captureManifestVersion(final SubProcessNetwork network, final AtomicLong sink) {
        return networkHapiTest(
                        "Capture manifest version on " + network.name(), network, withOpContext((spec, opLog) -> {
                            final var manifest = pollManifest(
                                    spec,
                                    m -> m.getVersion() >= FINALIZED_MANIFEST_MIN_VERSION
                                            && m.getEndpointsCount() >= 1);
                            sink.set(manifest.getVersion());
                            opLog.info(
                                    "Captured baseline manifest version {} on {}",
                                    manifest.getVersion(),
                                    network.name());
                        }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Polls {@code clprGetEndpointManifest} on {@code network} until the version has advanced to at
     * least {@code baseline.get() + 1} — proving the manifest was rebuilt in response to an endpoint
     * change.
     */
    static DynamicTest awaitManifestVersionAtLeast(final SubProcessNetwork network, final AtomicLong baseline) {
        return networkHapiTest(
                        "Await advanced manifest version on " + network.name(),
                        network,
                        withOpContext((spec, opLog) -> {
                            final long target = baseline.get() + 1;
                            final var manifest = pollManifest(spec, m -> m.getVersion() >= target);
                            opLog.info(
                                    "Manifest on {} advanced to version {} (>= {})",
                                    network.name(),
                                    manifest.getVersion(),
                                    target);
                        }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Polls {@code clprGetEndpointManifest} on {@code network} until its manifest has advanced past
     * {@code baseline} and advertises exactly the network's current nodes — one endpoint per node, on that
     * node's HAPI TLS port ({@code tlsPortBase + nodeId}) — then records that version into {@code sink}.
     * After a roster change this proves the manifest added every new node and dropped every departed one.
     * Deferred, so it reads the network's nodes as they are when it runs.
     */
    static DynamicTest awaitManifestMatchesRoster(
            final SubProcessNetwork network, final int tlsPortBase, final AtomicLong baseline, final AtomicLong sink) {
        return deferred("Await manifest of " + network.name() + " matching its roster", () -> networkHapiTest(
                        "Await manifest matching the roster of " + network.name(),
                        network,
                        withOpContext((spec, opLog) -> {
                            final var expectedPorts = network.nodes().stream()
                                    .map(node -> tlsPortBase + (int) node.getNodeId())
                                    .sorted()
                                    .toList();
                            final var manifest = pollManifest(
                                    spec,
                                    m -> m.getVersion() > baseline.get()
                                            && advertisedPorts(m).equals(expectedPorts));
                            sink.set(manifest.getVersion());
                            opLog.info(
                                    "Manifest of {} advanced {} -> {} and advertises ports {}",
                                    network.name(),
                                    baseline.get(),
                                    manifest.getVersion(),
                                    expectedPorts);
                        }))
                .findFirst()
                .orElseThrow());
    }

    private static List<Integer> advertisedPorts(final ClprEndpointManifest manifest) {
        return manifest.getEndpointsList().stream()
                .map(endpoint -> endpoint.getServiceEndpoint().getPort())
                .sorted()
                .toList();
    }

    /**
     * Awaits the receiver-side Step 1b log line proving {@code peer} applied the manifest at version
     * {@code version} — carried in a bundle proof or a recovery bundle — and that it advertises every port in
     * {@code ports}. Deferred, so it reads the peer's log as it is when it runs.
     */
    static DynamicTest awaitManifestAppliedOnPeer(
            final SubProcessNetwork peer, final AtomicLong version, final Supplier<List<Integer>> ports) {
        return deferred("Await manifest application on " + peer.name(), () -> networkHapiTest(
                        "Await Step 1b manifest application on " + peer.name(), peer, withOpContext((spec, opLog) -> {
                            final var line = new StringBuilder(
                                    "applying new endpoint manifest.*version=\\d+->" + version.get() + " .*endpoints=");
                            ports.get()
                                    .forEach(port ->
                                            line.append("(?=.*:").append(port).append("\\b)"));
                            awaitLogLine(peer, Pattern.compile(line.toString()), MANIFEST_APPEAR_TIMEOUT);
                            opLog.info(
                                    "Peer {} applied manifest version {} with ports {}",
                                    peer.name(),
                                    version.get(),
                                    ports.get());
                        }))
                .findFirst()
                .orElseThrow());
    }

    /**
     * Polls {@code clprGetEndpointManifest} until {@code predicate} holds or
     * {@link #MANIFEST_APPEAR_TIMEOUT} elapses (then fails). Returns the satisfying manifest.
     */
    public static ClprEndpointManifest pollManifest(
            final HapiSpec spec, final Predicate<ClprEndpointManifest> predicate) throws InterruptedException {
        final var deadline = Instant.now().plus(MANIFEST_APPEAR_TIMEOUT);
        final AtomicReference<ClprEndpointManifest> last = new AtomicReference<>();
        final AtomicReference<Exception> lastError = new AtomicReference<>();
        while (Instant.now().isBefore(deadline)) {
            try {
                allRunFor(spec, clprGetEndpointManifest().payingWith(GENESIS).exposingManifestTo(last::set));
                final var m = last.get();
                if (m != null && predicate.test(m)) {
                    return m;
                }
            } catch (final Exception e) {
                // Node may still be bootstrapping — retry, but keep the last error so a
                // timeout reports why the query kept failing rather than a bare "version=null".
                lastError.set(e);
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        final var m = last.get();
        final var err = lastError.get();
        assertTrue(
                m != null && predicate.test(m),
                "manifest predicate never satisfied (last version=" + (m == null ? "null" : m.getVersion())
                        + ", last query error=" + (err == null ? "none" : err.toString()) + ")");
        return m;
    }

    /**
     * Freeze + software-upgrade restart of {@code network}, keeping the same gRPC port
     * ({@code FakeNmt.restartWithConfigVersion} / {@code ReassignPorts.NO}) so cross-network CLPR
     * connectivity survives. On restart a node's address-book endpoint change is adopted, its in-memory
     * CLPR startup gate resets (so it re-publishes its endpoint), and the post-upgrade manifest prune
     * runs. Re-awaits WRAPS readiness so post-restart outbound bundles are peer-verifiable.
     */
    DynamicTest freezeUpgradeRestartSamePort(final SubProcessNetwork network) {
        return networkHapiTest(
                        "Freeze + upgrade restart " + network.name() + " (same port)",
                        network,
                        prepareFakeUpgrade(),
                        blockingOrder(
                                runBackgroundTrafficUntilFreezeComplete(),
                                sourcing(() -> freezeUpgrade()
                                        .startingIn(2)
                                        .seconds()
                                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                                confirmFreezeAndShutdown(),
                                sourcing(
                                        () -> FakeNmt.restartWithConfigVersion(allNodes(), nextConfigVersion(network))),
                                waitForActive(allNodes(), RESTART_TO_ACTIVE_TIMEOUT),
                                blockingOrder(doAdhoc(() -> {
                                    awaitWrapsExtensible(network);
                                    awaitWrapsSyncPoint(network);
                                    network.awaitLedgerId(RESTART_TO_ACTIVE_TIMEOUT);
                                }))))
                .findFirst()
                .orElseThrow();
    }

    // ---- roster-change helpers (shared by the manifest suites) ----

    /**
     * Wraps a step so it is built only when it runs, not when the test's step stream is built. A plain
     * {@code networkHapiTest} captures the network's nodes when it is built, so a step that follows a roster
     * change in the same test must be deferred to target the nodes the network has by then.
     */
    static DynamicTest deferred(final String displayName, final Supplier<DynamicTest> step) {
        return DynamicTest.dynamicTest(
                displayName, () -> step.get().getExecutable().execute());
    }

    /**
     * Replaces nodes of {@code network} with as many new ones, through two DAB upgrades that keep every TSS
     * hand-off valid whatever roster the (shared) network starts from:
     * <ol>
     *   <li>{@link #stakeCurrentNodes stake} the nodes already in the roster, so they carry stake-sized weights;</li>
     *   <li>create the new nodes and upgrade to a roster with them added. With no stake on them they get zero
     *       weight in the candidate roster, so the nodes already in the roster hold all of its weight;</li>
     *   <li>{@link #enrollInHintsScheme enroll} the new nodes into the active hinTS scheme through stake
     *       rotations. TSS counts as "already in the roster" only the parties of the active hinTS scheme, and a
     *       node added at an upgrade is not one until a later construction finds its hinTS key published;</li>
     *   <li>delete the {@code count} lowest-id nodes that were already there, and upgrade to a roster without
     *       them. The new nodes now sign and carry stake, and every node left was in the previous scheme.</li>
     * </ol>
     * The manifest reconciler rebuilds the endpoint manifest at each roster change, so the manifest advances
     * at least once and ends up advertising exactly the nodes left.
     *
     * @param count how many nodes to replace, given the network as it is when the replacement starts
     * @param addedNodeIds receives the ids of the new nodes
     */
    Stream<DynamicTest> replaceNodes(
            final SubProcessNetwork network,
            final ToIntFunction<SubProcessNetwork> count,
            final List<Long> addedNodeIds) {
        final AtomicInteger replaced = new AtomicInteger();
        return Stream.of(
                deferred("Stake the current nodes of " + network.name(), () -> stakeCurrentNodes(network)),
                deferred("Add new nodes to " + network.name(), () -> {
                    replaced.set(count.applyAsInt(network));
                    return addNodes(network, replaced.get(), addedNodeIds);
                }),
                deferred(
                        "Enroll the new nodes of " + network.name() + " into the hinTS scheme",
                        () -> enrollInHintsScheme(network, addedNodeIds)),
                deferred(
                        "Delete replaced nodes of " + network.name(),
                        () -> deleteOldestNodes(network, replaced.get(), addedNodeIds)));
    }

    /** Creates {@code count} nodes with the next node ids and upgrades {@code network} to a roster with them. */
    private DynamicTest addNodes(final SubProcessNetwork network, final int count, final List<Long> addedNodeIds) {
        final long firstNodeId = network.maxNodeId() + 1;
        final var nodeIds =
                LongStream.range(firstNodeId, firstNodeId + count).boxed().toList();
        addedNodeIds.addAll(nodeIds);
        final List<SpecOperation> ops = new ArrayList<>();
        final List<SpecOperation> preRestartOps = new ArrayList<>();
        for (final long nodeId : nodeIds) {
            final long accountNum = classicFeeCollectorIdFor(nodeId);
            ops.add(nodeCreate("node" + nodeId, accountNum)
                    .adminKey(GENESIS)
                    .description("node" + nodeId)
                    // The endpoints and gossip certificate the network assigns the node when it is added, so the
                    // candidate roster built from state matches the roster the restarted nodes adopt
                    .serviceEndpoint(List.of(network.grpcEndpointFor(nodeId)))
                    // In address-book order, which reverses the roster entry's (see snapshotOfFutureRoster)
                    .gossipEndpoint(network.gossipEndpointsFor(nodeId).reversed())
                    .gossipCaCertificate(gossipCaCertificateFor(nodeId))
                    .exposingCreatedIdTo(createdId ->
                            assertEquals(nodeId, createdId, "node id of the node created on " + network.name())));
            // The node pays for the bundles it submits from its own account
            ops.add(cryptoTransfer(tinyBarsFromTo(GENESIS, String.valueOf(accountNum), ADDED_NODE_ACCOUNT_FUNDING)));
            preRestartOps.add(FakeNmt.addNode(nodeId));
        }
        for (final long nodeId : nodeIds) {
            // addNode writes a new node's genesis network as of that node, so rewrite it once all are added; and it
            // only initializes the working dir, so also write the node's CLPR properties, grpc.tlsPort and HAPI TLS
            // certificate before it first starts
            preRestartOps.add(doAdhoc(() -> {
                network.refreshGenesisNetwork(nodeId);
                network.runPostInitWorkingDirActions(nodeId);
            }));
        }
        ops.add(upgradeWithRosterChange(network, List.of(), preRestartOps.toArray(SpecOperation[]::new)));
        return networkHapiTest("Add nodes " + nodeIds, network, ops.toArray(SpecOperation[]::new))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Makes every node in {@code addedNodeIds} a party of {@code network}'s active hinTS scheme, through weight
     * rotations driven by stake. Each rotation:
     * <ol>
     *   <li>stakes {@link #ADDED_NODE_STAKE} to each new node; the nodes already signing were staked
     *       {@link #EXISTING_NODE_STAKE} each before the new nodes were added, so they keep more than 2/3 of the
     *       rotated weight and the rotation's hinTS construction is not inert;</li>
     *   <li>waits for the next stake period, when the stakes become the candidate roster's weights;</li>
     *   <li>waits for the rotation's hinTS scheme and WRAPS history proof, after which the network hands off to
     *       the rotation at once.</li>
     * </ol>
     * The new nodes are targets of the first rotation, so they publish their hinTS keys during it, but its scheme
     * is assembled as soon as the nodes already signing have published. A later rotation of the same party count
     * then finds their keys published from the start and includes them; this repeats until a rotation's scheme
     * started with every new node's key, or fails after {@link #MAX_ENROLLMENT_ROTATIONS}.
     */
    private static DynamicTest enrollInHintsScheme(final SubProcessNetwork network, final List<Long> addedNodeIds) {
        return networkHapiTest(
                        "Enroll nodes " + addedNodeIds + " into the hinTS scheme",
                        network,
                        withOpContext((spec, opLog) -> {
                            final long periodMins = spec.startupProperties().getLong("staking.periodMins");
                            for (int rotation = 1; rotation <= MAX_ENROLLMENT_ROTATIONS; rotation++) {
                                final long mark = logLineCount(network);
                                final List<SpecOperation> stakes = new ArrayList<>();
                                addedNodeIds.forEach(nodeId -> stakes.add(stakeOp(nodeId, ADDED_NODE_STAKE)));
                                allRunFor(spec, stakes);
                                // Stakes are recomputed, and the rotation started, while handling the first
                                // transaction of the next stake period
                                allRunFor(
                                        spec,
                                        waitUntilStartOfNextStakingPeriod(periodMins),
                                        cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)));
                                final var hintsConstruction = awaitLogLineAfter(
                                        network,
                                        mark,
                                        Pattern.compile("Construction #(\\d+) already has \\d+ relevant keys "
                                                + "published \\[(.*)\\]"),
                                        ROTATION_TSS_TIMEOUT);
                                final var constructionId = hintsConstruction.group(1);
                                final var signers = hintsConstruction.group(2);
                                awaitLogLineAfter(
                                        network,
                                        mark,
                                        Pattern.compile(
                                                "Completed hinTS scheme for construction #" + constructionId + " "),
                                        ROTATION_TSS_TIMEOUT);
                                final var historyConstructionId = awaitLogLineAfter(
                                                network,
                                                mark,
                                                Pattern.compile("Created NEXT construction #(\\d+) for rosters"),
                                                ROTATION_TSS_TIMEOUT)
                                        .group(1);
                                awaitLogLineAfter(
                                        network,
                                        mark,
                                        Pattern.compile("History proof constructed \\(#" + historyConstructionId
                                                + ", WRAPS-extensible\\? true\\)"),
                                        ROTATION_TSS_TIMEOUT);
                                final boolean enrolled = addedNodeIds.stream()
                                        .allMatch(nodeId -> signers.contains("(" + nodeId + " -> "));
                                opLog.info(
                                        "Rotation {} of {}: hinTS construction #{} started with keys [{}]{}",
                                        rotation,
                                        network.name(),
                                        constructionId,
                                        signers,
                                        enrolled ? " — all of " + addedNodeIds + " enrolled" : "");
                                if (enrolled) {
                                    return;
                                }
                            }
                            throw new AssertionError("Nodes " + addedNodeIds + " of " + network.name()
                                    + " were not enrolled into the hinTS scheme within " + MAX_ENROLLMENT_ROTATIONS
                                    + " stake rotations");
                        }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Stakes {@link #EXISTING_NODE_STAKE} to every current node of {@code network}, and waits for the resulting weight
     * rotation's history proof. Done before adding nodes, so the nodes already in the roster carry stake-sized weights:
     * with only unit weights, two or more zero-weight new nodes make the platform's weighted sync-lag median 0/0
     * (see {@code SyncLagCalculator}), which blocks event creation until the network deadlocks.
     */
    private static DynamicTest stakeCurrentNodes(final SubProcessNetwork network) {
        return networkHapiTest("Stake the current nodes of " + network.name(), network, withOpContext((spec, opLog) -> {
                    final long mark = logLineCount(network);
                    allRunFor(
                            spec,
                            network.nodes().stream()
                                    .map(node -> stakeOp(node.getNodeId(), EXISTING_NODE_STAKE))
                                    .toList());
                    // Stakes are recomputed, and the weight rotation started, while handling the first
                    // transaction of the next stake period
                    allRunFor(
                            spec,
                            waitUntilStartOfNextStakingPeriod(
                                    spec.startupProperties().getLong("staking.periodMins")),
                            cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)));
                    final var constructionId = awaitLogLineAfter(
                                    network,
                                    mark,
                                    Pattern.compile("Created NEXT construction #(\\d+) for rosters"),
                                    ROTATION_TSS_TIMEOUT)
                            .group(1);
                    awaitLogLineAfter(
                            network,
                            mark,
                            Pattern.compile("History proof constructed \\(#" + constructionId
                                    + ", WRAPS-extensible\\? true\\)"),
                            ROTATION_TSS_TIMEOUT);
                    opLog.info(
                            "Staked the current nodes of {} (history construction #{})",
                            network.name(),
                            constructionId);
                }))
                .findFirst()
                .orElseThrow();
    }

    /** Stakes {@code amount} to node {@code nodeId} from a new account that declines rewards, so it stays put. */
    private static SpecOperation stakeOp(final long nodeId, final long amount) {
        return cryptoCreate("stakerOfNode" + nodeId + "-" + STAKER_SEQ.incrementAndGet())
                .balance(amount)
                .stakedNodeId(nodeId)
                .declinedReward(true);
    }

    /**
     * Deletes the {@code count} lowest-id nodes of {@code network} that are not in {@code keptNodeIds} and
     * upgrades it to a roster without them.
     */
    private DynamicTest deleteOldestNodes(
            final SubProcessNetwork network, final int count, final List<Long> keptNodeIds) {
        final var departed = network.nodes().stream()
                .filter(node -> !keptNodeIds.contains(node.getNodeId()))
                .sorted(Comparator.comparingLong(HederaNode::getNodeId))
                .limit(count)
                .toList();
        final var departedNodeIds = departed.stream().map(HederaNode::getNodeId).toList();
        final List<SpecOperation> ops = new ArrayList<>();
        departedNodeIds.forEach(nodeId -> ops.add(nodeDelete(String.valueOf(nodeId))));
        ops.add(upgradeWithRosterChange(
                network,
                departed.stream()
                        .map(node -> node.getHost() + ":" + node.getGrpcPort())
                        .toList(),
                departedNodeIds.stream()
                        .map(nodeId -> FakeNmt.removeNode(byNodeId(nodeId)))
                        .toArray(SpecOperation[]::new)));
        return networkHapiTest("Delete nodes " + departedNodeIds, network, ops.toArray(SpecOperation[]::new))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Freeze + software upgrade of {@code network} to the roster its node store now implies, keeping every
     * surviving node's ports. Before freezing it waits until the candidate roster can be adopted (see
     * {@link #awaitCandidateRosterAdoptable}); {@code preRestartOps} then add or remove the nodes while the
     * network is down. Afterwards it rebuilds the network's clients for its new nodes and re-awaits WRAPS
     * readiness so post-upgrade outbound bundles are peer-verifiable.
     *
     * @param departedNodeUris the {@code host:port} gRPC URIs of the nodes the upgrade removes
     */
    private SpecOperation upgradeWithRosterChange(
            final SubProcessNetwork network,
            final List<String> departedNodeUris,
            final SpecOperation... preRestartOps) {
        return blockingOrder(
                // Each node exports the candidate roster at PREPARE_UPGRADE; drop earlier upgrades' exports first
                doAdhoc(() -> network.nodes().forEach(node -> deleteCandidateRosterExport(node))),
                prepareFakeUpgrade(),
                doAdhoc(() -> awaitCandidateRosterAdoptable(network)),
                runBackgroundTrafficUntilFreezeComplete(),
                sourcing(() -> freezeUpgrade()
                        .startingIn(2)
                        .seconds()
                        .withUpdateFile(DEFAULT_UPGRADE_FILE_ID)
                        .havingHash(upgradeFileHashAt(FAKE_UPGRADE_ZIP_LOC))),
                confirmFreezeAndShutdown(),
                blockingOrder(preRestartOps),
                // FakeNmt writes an override-network.json for every node not starting from genesis, which makes
                // the restarted nodes adopt the harness's roster through the override path. Drop it, so they adopt
                // the candidate roster from state through canAdoptRoster, as a mainnet upgrade does.
                doAdhoc(() -> network.nodes().forEach(HieroToHieroBase::deleteOverrideNetwork)),
                sourcing(() -> FakeNmt.restartWithConfigVersion(allNodes(), nextConfigVersion(network))),
                // A new node first reconnects to get the state
                waitForActive(allNodes(), ROSTER_CHANGE_TO_ACTIVE_TIMEOUT),
                doAdhoc(() -> {
                    // Every node restarted, so channels opened before the restart may still be backing off from
                    // their failed connections; replace them all, along with the departed nodes' channels
                    final List<String> staleNodeUris = new ArrayList<>(departedNodeUris);
                    network.nodes().forEach(node -> staleNodeUris.add(node.getHost() + ":" + node.getGrpcPort()));
                    network.refreshClientsForCurrentNodes(staleNodeUris);
                    awaitWrapsExtensible(network);
                    awaitWrapsSyncPoint(network);
                    network.awaitLedgerId(RESTART_TO_ACTIVE_TIMEOUT);
                }));
    }

    /** Number of lines currently in the {@code hgcaa.log} {@link #awaitLogLine} scans for {@code network}. */
    private static long logLineCount(final SubProcessNetwork network) {
        final Path logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        try (var lines = Files.lines(logPath)) {
            return lines.count();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * As {@link #awaitLogLine}, but only matches lines after the first {@code mark} lines, so a step can wait for
     * a log line its own actions caused rather than an earlier occurrence.
     */
    private static MatchResult awaitLogLineAfter(
            final SubProcessNetwork network, final long mark, final Pattern pattern, final Duration timeout) {
        final Path logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        final Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (var lines = Files.lines(logPath)) {
                final var match = lines.skip(mark)
                        .map(pattern::matcher)
                        .filter(Matcher::find)
                        .findFirst();
                if (match.isPresent()) {
                    return match.get().toMatchResult();
                }
            } catch (final IOException ignored) {
                // log may be mid-rotation; retry
            }
            sleepFor(POLL_INTERVAL);
        }
        throw new AssertionError("Network '" + network.name() + "' never logged pattern /" + pattern + "/ after line "
                + mark + " within " + timeout);
    }

    private static void deleteOverrideNetwork(final HederaNode node) {
        try {
            Files.deleteIfExists(node.getExternalPath(DATA_CONFIG_DIR).resolve(OVERRIDE_NETWORK_JSON));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteCandidateRosterExport(final HederaNode node) {
        try {
            Files.deleteIfExists(node.metadata().workingDirOrThrow().resolve(CANDIDATE_ROSTER_JSON));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Blocks until {@code network} can adopt the candidate roster it exported at {@code PREPARE_UPGRADE}: a node
     * adopts a roster at an upgrade only once the WRAPS history proof for it (which requires its hinTS scheme)
     * is complete, and refuses to start on an override roster before that. Finds the history construction
     * targeting the candidate roster's hash, then waits for its proof.
     */
    private static void awaitCandidateRosterAdoptable(final SubProcessNetwork network) {
        final Path export =
                network.nodes().getFirst().metadata().workingDirOrThrow().resolve(CANDIDATE_ROSTER_JSON);
        final var deadline = Instant.now().plus(CANDIDATE_ROSTER_EXPORT_TIMEOUT);
        Optional<Network> candidate = Optional.empty();
        while (candidate.isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(
                        "Network '" + network.name() + "' did not export a candidate roster at " + export);
            }
            if (Files.exists(export)) {
                try {
                    candidate = DiskStartupNetworks.loadNetworkFrom(export);
                } catch (final RuntimeException ignored) {
                    // still being written; retry
                }
            }
            sleepFor(POLL_INTERVAL);
        }
        final var targetHash = RosterUtils.hash(RosterUtils.rosterFrom(candidate.get()))
                .getBytes()
                .toHex();
        final var constructionId = awaitLogLine(
                        network,
                        Pattern.compile("Created NEXT construction #(\\d+) for rosters \\(source=[0-9a-f]+, target="
                                + targetHash + "\\)"),
                        CANDIDATE_ROSTER_TSS_TIMEOUT)
                .group(1);
        awaitLogLine(
                network,
                Pattern.compile("History proof constructed \\(#" + constructionId + ", WRAPS-extensible\\? true\\)"),
                CANDIDATE_ROSTER_TSS_TIMEOUT);
        log.info(
                "{} can adopt candidate roster {} (history construction #{})",
                network.name(),
                targetHash,
                constructionId);
    }

    /** The gossip CA certificate {@link SubProcessNetwork} generates for node {@code nodeId}. */
    private static byte[] gossipCaCertificateFor(final long nodeId) {
        try {
            final var nodeKeys = KeysAndCertsGenerator.generateKeysAndCerts(List.of(NodeId.of(nodeId)));
            return nodeKeys.get(NodeId.of(nodeId)).sigCert().getEncoded();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate the gossip certificate of node" + nodeId, e);
        }
    }

    // ---- partition helpers ----

    /**
     * Freezes and shuts down every node of {@code network}, cutting it off from its peers (and, while down, from
     * any roster change they make) until {@link #restartLedger}.
     */
    static DynamicTest shutDownLedger(final SubProcessNetwork network) {
        return deferred("Shut down " + network.name(), () -> networkHapiTest(
                        "Freeze and shut down " + network.name(),
                        network,
                        freezeOnly().startingIn(2).seconds(),
                        waitForFrozenNetwork(FREEZE_TIMEOUT),
                        FakeNmt.shutdownWithin(allNodes(), SHUTDOWN_TIMEOUT))
                .findFirst()
                .orElseThrow());
    }

    /**
     * Returns a new config version for an upgrade of {@code network} and records it as the version the ledger runs,
     * so {@link #restartLedger} can bring it back on that same version.
     */
    private static int nextConfigVersion(final SubProcessNetwork network) {
        final int configVersion = CURRENT_CONFIG_VERSION.incrementAndGet();
        CONFIG_VERSIONS.put(network.name(), configVersion);
        return configVersion;
    }

    /**
     * Restarts every node of a {@link #shutDownLedger shut down} {@code network} on its same ports and waits
     * until its block proofs are peer-verifiable again. Runs outside a spec, since a spec cannot target a network
     * with no live node.
     */
    static DynamicTest restartLedger(final SubProcessNetwork network) {
        return DynamicTest.dynamicTest("Restart " + network.name(), () -> {
            awaitPortsFree(network.nodes());
            // An outage, not an upgrade: come back on the version the ledger last ran
            final int configVersion = CONFIG_VERSIONS.getOrDefault(network.name(), 0);
            network.nodes().forEach(node -> ((SubProcessNode) node).startWithConfigVersion(configVersion));
            network.nodes().forEach(node -> awaitStatus(node, RESTART_TO_ACTIVE_TIMEOUT, ACTIVE));
            awaitWrapsSyncPoint(network);
            network.awaitLedgerId(RESTART_TO_ACTIVE_TIMEOUT);
        });
    }

    /**
     * Blocks until every TCP port the given nodes rebind on a same-port restart (gRPC, node-operator gRPC,
     * internal/external gossip, prometheus) is actually releasable, so the restarted process can bind without a
     * {@link java.net.BindException}. Probes each port by attempting a throwaway bind and returns as soon as all
     * are free (typically seconds, since a killed process releases its listening sockets almost immediately), or
     * throws after {@link #PORT_FREE_TIMEOUT} if a port is still held.
     */
    static void awaitPortsFree(final List<HederaNode> nodes) {
        final var ports = nodes.stream()
                .flatMap(n -> Stream.of(
                        n.metadata().grpcPort(),
                        n.metadata().grpcNodeOperatorPort(),
                        n.metadata().internalGossipPort(),
                        n.metadata().externalGossipPort(),
                        n.metadata().prometheusPort()))
                .distinct()
                .toList();
        final var deadline = Instant.now().plus(PORT_FREE_TIMEOUT);
        for (final int port : ports) {
            while (!isPortFree(port)) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Port " + port + " still bound after " + PORT_FREE_TIMEOUT
                            + "; the killed node did not release it");
                }
                sleepFor(PORT_FREE_POLL);
            }
        }
    }

    /**
     * Returns true if {@code port} is bindable on loopback right now. Uses {@code SO_REUSEADDR=false} so it
     * detects the same conflicts the node's own (non-reusing) bind would hit — it never reports a
     * still-held port as free.
     */
    private static boolean isPortFree(final int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    private static void sleepFor(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", e);
        }
    }

    /**
     * Full two-sided setup orchestrated as five phases:
     * <ol>
     *   <li>{@link #installLedgerConfig install} the local LedgerConfiguration on each network;</li>
     *   <li>{@link #captureConfigProof await} the first WRAPS-extensible recursive proof, then
     *       capture each network's config StateProof via {@code clprGetLedgerConfiguration}, gating on an
     *       in-process freshness check ({@code initial_trust_anchor} populated + throttles current) so a
     *       stale or pre-install proof never reaches {@code deployAndConnect};</li>
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
        final AtomicReference<ByteString> proofA = new AtomicReference<>();
        final AtomicReference<ByteString> proofB = new AtomicReference<>();
        // Manifest-only mode: completeChannel and the verifyConfig probes both require each
        // ledger's endpoint-manifest StateProof, so capture and thread them like the HAPI TLS overload.
        final AtomicReference<ByteString> manifestProofA = new AtomicReference<>();
        final AtomicReference<ByteString> manifestProofB = new AtomicReference<>();
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
                proofA,
                proofB,
                manifestProofA,
                manifestProofB,
                crypto,
                maxMessagesPerBundle,
                maxQueueDepth,
                bothProofsReady);
        final var chainB = chainSetupAndConnect(
                ledgerB,
                ledgerA,
                "hiero:299",
                proofB,
                proofA,
                manifestProofB,
                manifestProofA,
                crypto,
                maxMessagesPerBundle,
                maxQueueDepth,
                bothProofsReady);
        return Stream.of(networkHapiTest(
                        "Install + capture + verify + deploy on both ledgers (parallel)",
                        ledgerA,
                        new ParallelSpecOps(chainA, chainB).failOnErrors())
                .findFirst()
                .orElseThrow());
    }

    /**
     * As {@link #setupBothNetworks(SubProcessNetwork, SubProcessNetwork, int, int, ClprCrypto,
     * int, int)} but additionally captures each ledger's manifest {@code StateProof} via the
     * {@code clprGetEndpointManifest} HAPI query and threads it into the peer's
     * {@code ClprCompleteChannel} as {@code endpoint_manifest_proof_bytes} (required, since the
     * manifest-aware verifier ABI rejects an empty manifest proof). Dial targets, including each
     * network's HAPI TLS port and certificate, come from the captured manifests.
     *
     * <p>On {@code enableHapiTls} networks each node self-publishes its {@code grpc.tlsPort} and HAPI TLS
     * certificate (as the endpoint {@code tls_certificate}), and {@code portA}/{@code portB} are expected to
     * be those networks' {@code grpc.tlsPort} bases. This is the combined <b>HAPI TLS + manifest-proof</b>
     * path: the channel completes over — and syncs across — the HAPI TLS listener.
     *
     * <p>Callers must guarantee the reconciler has finalized a manifest on both networks
     * before this runs (e.g. by preceding it with a manifest-await step); the capture inside
     * this method is a single query, not a poll.
     */
    static Stream<DynamicTest> setupBothNetworksWithManifestProof(
            final SubProcessNetwork ledgerA,
            final SubProcessNetwork ledgerB,
            final int portA,
            final int portB,
            final ClprCrypto crypto) {
        final AtomicReference<ByteString> proofA = new AtomicReference<>();
        final AtomicReference<ByteString> proofB = new AtomicReference<>();
        final AtomicReference<ByteString> manifestProofA = new AtomicReference<>();
        final AtomicReference<ByteString> manifestProofB = new AtomicReference<>();
        final var bothProofsReady = new CountDownLatch(2);
        final var chainA = chainSetupAndConnect(
                ledgerA,
                ledgerB,
                "hiero:298",
                proofA,
                proofB,
                manifestProofA,
                manifestProofB,
                crypto,
                DEFAULT_MAX_MESSAGES_PER_BUNDLE,
                DEFAULT_MAX_QUEUE_DEPTH,
                bothProofsReady);
        final var chainB = chainSetupAndConnect(
                ledgerB,
                ledgerA,
                "hiero:299",
                proofB,
                proofA,
                manifestProofB,
                manifestProofA,
                crypto,
                DEFAULT_MAX_MESSAGES_PER_BUNDLE,
                DEFAULT_MAX_QUEUE_DEPTH,
                bothProofsReady);
        return Stream.of(networkHapiTest(
                        "Install + capture (config + manifest) + verify + deploy on both ledgers (HAPI TLS, parallel)",
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
     *
     * <p>When {@code selfManifestProof} and {@code peerManifestProof} are non-null, it also captures the
     * local manifest state proof into {@code selfManifestProof} after the config proof capture, and
     * passes {@code peerManifestProof} into {@link #deployAndConnect} as
     * {@code endpoint_manifest_proof_bytes}.
     */
    private static SpecOperation chainSetupAndConnect(
            final SubProcessNetwork self,
            final SubProcessNetwork peer,
            final String selfChainId,
            final AtomicReference<ByteString> selfProof,
            final AtomicReference<ByteString> peerProof,
            final AtomicReference<ByteString> selfManifestProof,
            final AtomicReference<ByteString> peerManifestProof,
            final ClprCrypto crypto,
            final int maxMessagesPerBundle,
            final int maxQueueDepth,
            final CountDownLatch bothProofsReady) {
        return withOpContext((ignoredSpec, ignoredLog) -> {
            try {
                installLedgerConfig(self, selfChainId, maxMessagesPerBundle, maxQueueDepth)
                        .getExecutable()
                        .execute();
                captureConfigProof(self, selfProof, maxMessagesPerBundle, maxQueueDepth)
                        .getExecutable()
                        .execute();
                if (selfManifestProof != null) {
                    captureManifestProof(self, selfManifestProof)
                            .getExecutable()
                            .execute();
                }
            } finally {
                // Signal our capture is done — even when it threw, so the peer chain fails on our missing
                // proof below instead of blocking forever in await().
                bothProofsReady.countDown();
            }
            // Wait for the peer chain to reach the same point before dispatching deployAndConnect
            // (which uses the peer's proof).
            if (!bothProofsReady.await(BOTH_PROOFS_READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Peer '" + peer.name() + "' did not finish capturing its proofs within "
                        + BOTH_PROOFS_READY_TIMEOUT);
            }
            if (isNullOrEmpty(peerProof) || (peerManifestProof != null && isNullOrEmpty(peerManifestProof))) {
                throw new IllegalStateException(
                        "Peer '" + peer.name() + "' failed to capture its config or endpoint-manifest proof");
            }
            deployAndConnect(self, peer, crypto, peerProof, peerManifestProof)
                    .getExecutable()
                    .execute();
        });
    }

    private static boolean isNullOrEmpty(final AtomicReference<ByteString> proof) {
        final var captured = proof.get();
        return captured == null || captured.isEmpty();
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
     * pay gas for the verifier-contract dispatch when handling inbound bundles.
     */
    private static DynamicTest installLedgerConfig(
            final SubProcessNetwork network,
            final String ownChainId,
            final int maxMessagesPerBundle,
            final int maxQueueDepth) {
        return networkHapiTest(
                        "Install ledger config (" + ownChainId + ")",
                        network,
                        // Wait for the consensus history ledger id and signed snapshots to be ready.
                        // The handler reads the trust anchor from consensus history; subsequent
                        // captureConfigProof calls also need a snapshot carrying that identity. Sequence:
                        //   1. WRAPS-extensible — proving state is loaded.
                        //   2. [CLPR-SYNC-POINT] — the FIRST block proof embeds the WRAPS recursive
                        //      proof. On warm-cache this is block #0; the snapshot for that block is
                        //      captured DURING its proof construction, which races with the WRAPS
                        //      embed → its ledger_id can still be empty.
                        //   3. POST_SYNC_POINT_SETTLE — give the system time to sign at least one
                        //      MORE block, whose snapshot is captured strictly after the WRAPS-
                        //      embed, so latestSnapshot().ledgerId() is guaranteed non-empty.
                        // Without this, the peer's proof verification may run before TSS is ready.
                        withOpContext((spec, ignored) -> {
                            awaitWrapsExtensible(network);
                            awaitWrapsSyncPoint(network);
                            Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                        }),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, "3", 100_000_000_000L)),
                        clprUpdateLedgerConfiguration()
                                .configuration(buildLedgerConfig(ownChainId, maxMessagesPerBundle, maxQueueDepth))
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
                    awaitWrapsExtensible(network);
                    // Poll for the [CLPR-SYNC-POINT] log emitted by BlockStreamManagerImpl when the
                    // first block proof embeds the WRAPS recursive proof, then briefly settle so a few
                    // WRAPS-carrying blocks accumulate before capture.
                    awaitWrapsSyncPoint(network);
                    Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                    final var deadline = Instant.now().plus(MANIFEST_APPEAR_TIMEOUT);
                    while (Instant.now().isBefore(deadline)) {
                        sink.set(ByteString.EMPTY);
                        allRunFor(
                                spec,
                                clprGetLedgerConfiguration().payingWith(GENESIS).exposingProofTo(sink::set));
                        final var captured = sink.get();
                        // In-process freshness gate: decode the ClprLedgerConfiguration carried by the
                        // captured StateProof and require (a) a populated initial_trust_anchor and (b) the
                        // just-installed throttles. clprGetLedgerConfiguration proves against a SIGNED block
                        // that lags committed state, so an early capture can carry an empty trust anchor (the
                        // ledger_id isn't in the proven config yet) or a PRIOR install's throttles — either of
                        // which would make the peer's clprCompleteChannel verifyConfig revert. Decoding in
                        // process (rather than calling the verifier precompile) gives the same guarantee
                        // without the 6 KB call-size limit the config+manifest proofs would exceed.
                        if (captured != null
                                && !captured.isEmpty()
                                && configProofIsFresh(
                                        captured.toByteArray(), expectedMaxMessagesPerBundle, expectedMaxQueueDepth)) {
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
     * Captures the latest {@code manifest_state_proof} bytes for {@code network} via the
     * {@code clprGetEndpointManifest} HAPI query. Polls until the proof is non-empty and the
     * manifest is finalized (version &gt;= 2, endpoints &gt;= 1) — proving the reconciler-driven
     * self-publication has landed and closed a construction.
     */
    public static DynamicTest captureManifestProof(
            final SubProcessNetwork network, final AtomicReference<ByteString> sink) {
        return networkHapiTest("Capture endpoint-manifest StateProof", network, withOpContext((spec, ignored) -> {
                    final var deadline = Instant.now().plus(MANIFEST_APPEAR_TIMEOUT);
                    final long[] observedVersion = {0L};
                    final int[] observedEndpoints = {0};
                    while (Instant.now().isBefore(deadline)) {
                        sink.set(ByteString.EMPTY);
                        allRunFor(
                                spec,
                                clprGetEndpointManifest()
                                        .payingWith(GENESIS)
                                        .exposingManifestTo(m -> {
                                            observedVersion[0] = m.getVersion();
                                            observedEndpoints[0] = m.getEndpointsCount();
                                        })
                                        .exposingProofTo(sink::set));
                        final var captured = sink.get();
                        if (captured != null
                                && !captured.isEmpty()
                                && observedVersion[0] >= 2L
                                && observedEndpoints[0] >= 1) {
                            return;
                        }
                        Thread.sleep(POLL_INTERVAL.toMillis());
                    }
                    throw new IllegalStateException("Network '" + network.name() + "' never produced a finalized "
                            + "manifest state proof (last observed version="
                            + observedVersion[0] + " endpoints=" + observedEndpoints[0] + ")");
                }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Manual manifest recovery (spec §8.1.4 / §8.1.2 / §8.1.3): submits {@code proofSink}'s captured
     * remote {@code manifest_state_proof} as a bundle on {@code network} via {@code clprSubmitBundle}.
     * This is the out-of-band recovery path — no gRPC connectivity to any (stale) peer endpoint is used;
     * the local ledger verifies the remote proof against the Channel's trust anchor.
     *
     * <p>Accepts any of {@code acceptableStatuses}. A {@code SUCCESS} means this submit is what accepted
     * the manifest-only recovery bundle (no channel leaf) and applied the advancing manifest. A
     * {@code CLPR_BUNDLE_VERIFICATION_FAILED} means the manifest was <em>already</em> applied — on a
     * one-sided turnover the surviving link lets the remote auto-push its manifest first, so the manual
     * submit legitimately races that automatic recovery. The <em>deterministic</em> assertion is the
     * separate {@link #awaitManifestAppliedOnPeer} step: the manifest is applied regardless of which path
     * wins, and when all connectivity is broken (both-sided turnover) that can only be this manual submit.
     */
    static DynamicTest submitManifestRecoveryBundle(
            final SubProcessNetwork network,
            final byte[] channelId,
            final AtomicReference<ByteString> proofSink,
            final ResponseCodeEnum... acceptableStatuses) {
        return networkHapiTest(
                        "Manual manifest recovery via clprSubmitBundle on " + network.name(),
                        network,
                        withOpContext((spec, opLog) -> {
                            final var proof = proofSink.get();
                            if (proof == null || proof.isEmpty()) {
                                throw new IllegalStateException(
                                        "no captured manifest state proof to submit for recovery on " + network.name());
                            }
                            allRunFor(
                                    spec,
                                    clprSubmitBundle()
                                            .channelId(channelId)
                                            .bundlePayload(proof.toByteArray())
                                            .endpointNodeId(0L)
                                            .payingWith(GENESIS)
                                            .hasKnownStatusFrom(acceptableStatuses));
                            opLog.info("Manual manifest recovery submitted on {}", network.name());
                        }))
                .findFirst()
                .orElseThrow();
    }

    /**
     * True when the config {@link com.hedera.hapi.block.stream.StateProof} decodes to a
     * {@code ClprLedgerConfiguration} that carries a populated {@code initial_trust_anchor} and the
     * just-installed throttles — i.e. the proof is committed and current, so the peer's
     * {@code clprCompleteChannel} verifyConfig will accept it.
     *
     * <p>{@code clprGetLedgerConfiguration} proves against a SIGNED block that lags committed state, so an
     * early capture can carry an empty trust anchor (the ledger_id isn't in the proven config yet) or a
     * PRIOR install's throttles on a shared network. Both would make the peer's completeChannel verifyConfig
     * revert. This is decoded in-process (see {@link ClprTestProofs#decodeLedgerConfig}) rather than via a
     * verifier-precompile call, so it is not subject to the 6 KB contract-call size limit that the
     * config+manifest proofs together would exceed.
     */
    private static boolean configProofIsFresh(
            final byte[] configProofBytes, final int expectedMaxMessagesPerBundle, final int expectedMaxQueueDepth) {
        final var config = ClprTestProofs.decodeLedgerConfig(configProofBytes);
        if (config == null || config.initialTrustAnchor().length() == 0) {
            return false;
        }
        final var throttles = config.throttles();
        return throttles != null
                && throttles.maxMessagesPerBundle() == expectedMaxMessagesPerBundle
                && throttles.maxQueueDepth() == expectedMaxQueueDepth;
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
            final AtomicReference<ByteString> peerConfigProof,
            final AtomicReference<ByteString> peerManifestProof) {
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
                        sourcing(() -> {
                            var op = clprCompleteChannel()
                                    .channelId(crypto.channelId)
                                    .publicKey(crypto.publicKey)
                                    .signature(crypto.channelSignature)
                                    .signatureScheme(ClprSignatureScheme.ED25519)
                                    .verifierContract(VERIFIER)
                                    .configProofBytes(peerConfigProof.get().toByteArray())
                                    .payingWith(GENESIS)
                                    // Fail loudly if the captured StateProof was stale — would otherwise
                                    // pass silently and downstream knownChannels would stay at 0.
                                    .hasKnownStatus(ResponseCodeEnum.SUCCESS);
                            // Manifest proof is REQUIRED (spec §4.8): the manifest-aware verifier
                            // ABI rejects an empty manifest proof, so callers must supply a captured
                            // proof when one is available.
                            if (peerManifestProof != null) {
                                op = op.endpointManifestProofBytes(
                                        peerManifestProof.get().toByteArray());
                            }
                            return op;
                        }),
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

    /**
     * Collapses a multi-network test's ordered steps into a <b>single</b> {@link DynamicTest} that runs
     * them sequentially in one executable. Each {@code @MultiNetworkHapiTest} method wraps its returned
     * stream in this so that, under {@code parallel.mode.default=concurrent}, the factory's steps do NOT
     * get parallelized (which would run e.g. an await before its send); instead the whole test is one
     * node, and only <em>different</em> test methods run concurrently (serialized per network by
     * {@code ClprNetworkLocksProvider}). The trade-off is coarser reporting: one reported test per method
     * rather than one per step (a failing step still surfaces via its spec name in the exception).
     */
    public static Stream<DynamicTest> multiNetworkHapiTest(final String name, final Stream<DynamicTest> steps) {
        final var ordered = steps.toList();
        return Stream.of(DynamicTest.dynamicTest(name, () -> {
            for (final var step : ordered) {
                step.getExecutable().execute();
            }
        }));
    }

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
     * 20-byte EVM address of the Hiero CLPR system contract precompile
     * ({@code 0x000000000000000000000000000000000000016e}) — same value the reconciler
     * pre-populates into the endpoint manifest at genesis (see {@code V0780ClprSchema}).
     * Using it here keeps {@code config.service_address == manifest.service_address}, an
     * invariant the manifest-aware verifier enforces (spec §4.8). {@link ClprCrypto} also
     * incorporates it into the connector signature.
     */
    static final byte[] CLPR_SERVICE_ADDRESS = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, (byte) 0x6e};

    static ClprLedgerConfiguration buildLedgerConfig(
            final String chainId, final int maxMessagesPerBundle, final int maxQueueDepth) {
        return ClprLedgerConfiguration.newBuilder()
                .setChainId(chainId)
                .setServiceAddress(ByteString.copyFrom(CLPR_SERVICE_ADDRESS))
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
        private static final byte[] TEST_PRIVATE_KEY_SEED = {
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

        final byte[] publicKey = new byte[Ed25519.PUBLIC_KEY_BYTES];
        final byte[] secretKey = new byte[Ed25519.SECRET_KEY_BYTES];
        final byte[] channelId;
        final byte[] channelCommitment;
        final byte[] channelSignature = new byte[Ed25519.SIGNATURE_BYTES];
        final byte[] connectorSalt = new byte[32]; // all zeros
        final byte[] connectorId;
        final byte[] connectorCommitment;
        final byte[] connectorSignature = new byte[Ed25519.SIGNATURE_BYTES];

        ClprCrypto() {
            Ed25519.newKeypair(publicKey, secretKey, TEST_PRIVATE_KEY_SEED);

            final var seed = "hiero-e2e-test-conn-v1-" + INSTANCE_COUNTER.incrementAndGet();
            channelId = MiscCryptoUtils.keccak256DigestOf(seed.getBytes(StandardCharsets.UTF_8));

            // Channel commitment: keccak256(channelId || pubKey)
            channelCommitment = MiscCryptoUtils.keccak256DigestOf(concat(channelId, publicKey));

            // Channel signature: Ed25519 over keccak256(channelId)
            final var connSigMsg = MiscCryptoUtils.keccak256DigestOf(channelId);
            Ed25519.sign(channelSignature, connSigMsg, secretKey);

            // connectorId = keccak256(channelId || pubKey || connectorSalt)
            connectorId = MiscCryptoUtils.keccak256DigestOf(concat(channelId, concat(publicKey, connectorSalt)));

            // Connector commitment: keccak256(connectorId || pubKey)
            connectorCommitment = MiscCryptoUtils.keccak256DigestOf(concat(connectorId, publicKey));

            // Connector signature: Ed25519 over keccak256(connectorId || CLPR_SERVICE_ADDRESS)
            final var connectorSigMsg = MiscCryptoUtils.keccak256DigestOf(concat(connectorId, CLPR_SERVICE_ADDRESS));
            Ed25519.sign(connectorSignature, connectorSigMsg, secretKey);
        }

        private static byte[] concat(final byte[] a, final byte[] b) {
            final byte[] r = new byte[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }
}
