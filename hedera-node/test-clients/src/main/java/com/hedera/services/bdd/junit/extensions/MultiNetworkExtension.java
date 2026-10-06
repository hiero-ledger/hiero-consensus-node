// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.remembering;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Provisions and injects isolated subprocess networks for {@link MultiNetworkHapiTest}-annotated
 * methods. Networks are started before each test method and terminated after.
 */
public class MultiNetworkExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final Logger log = LogManager.getLogger(MultiNetworkExtension.class);
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(MultiNetworkExtension.class);
    private static final String NETWORKS_KEY = "multiNetworks";
    private static final String PARAM_INDEXES_KEY = "networkParamIndexes";
    private static final String ANNOTATION_KEY = "multiNetworkAnnotation";
    private static final String SHARED_FLAG_KEY = "networksShared";
    private static final String CAPTURED_PROPS_KEY = "capturedProps";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
    /** How long to wait for an already-started shared network to be ACTIVE again before reusing it. */
    private static final Duration SHARED_REUSE_ACTIVE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Networks pre-started once per test plan by {@code SharedMultiNetworkLauncherSessionListener}
     * and reused across every {@link MultiNetworkHapiTest} method whose declared network names
     * resolve here. Empty unless the launcher-session listener populated it.
     */
    public static final Map<String, SubProcessNetwork> SHARED_NETWORKS = new ConcurrentHashMap<>();

    static final String CLPR_MTLS_PORT_KEY = "clpr.mtlsPort";
    static final String CLPR_CA_CRT_PATH_KEY = "clpr.caCrtPath";
    static final String CLPR_CA_KEY_PATH_KEY = "clpr.caKeyPath";

    /**
     * Relative path (from a node's working dir) where {@link #provisionClprMtls} drops the CA PEMs when
     * {@code enableClprMtls = true}; also the value auto-seeded for {@code clpr.caCrtPath} /
     * {@code clpr.caKeyPath}, so the write location and the advertised config value share one definition.
     */
    private static final String CLPR_CA_CRT_PATH = "data/clpr/ca.crt";

    private static final String CLPR_CA_KEY_PATH = "data/clpr/ca.key";

    /**
     * Setup-override keys that are node-local and read ONLY at JVM startup (not dynamically reloadable):
     * the CLPR mTLS cert paths and per-node mTLS listener port. These are seeded per node into
     * {@code application.properties} by {@link #seedPerNodeApplicationOverrides}; applying them via the
     * runtime 0.0.121 network override (see {@link #applySetupOverrides}) is a no-op at runtime and would
     * shadow a later per-node {@code application.properties} change on restart (breaking mtlsPort rotation).
     */
    private static final Set<String> STARTUP_ONLY_NODE_KEYS =
            Set.of(CLPR_MTLS_PORT_KEY, CLPR_CA_CRT_PATH_KEY, CLPR_CA_KEY_PATH_KEY);

    /**
     * Deterministic port allocation for multi-network tests.
     *
     * <p>By default {@code Network#firstGrpcPort()} is {@code -1}, which makes
     * {@link SubProcessNetwork} pick a random base port in {@code [30000, 40000)}. With multiple
     * concurrently-running shared networks that random draw can land inside another network's
     * reserved window, causing bind-time port collisions.
     *
     * <p>Every allocation — explicit or auto — is recorded as a {@link Reservation} in
     * {@link #RESERVATIONS_BY_NAME}. Auto allocations scan the shared pool
     * {@code [SHARED_PORT_BASE, SHARED_PORT_BASE + SHARED_POOL_SLOTS * SHARED_PORT_SLOT)} for
     * the first slot that doesn't overlap any prior reservation. Explicit allocations fail loudly
     * if their range overlaps any prior reservation (including ranges outside the pool). Repeat
     * lookups for the same name return the cached base so both discovery paths (per-test boot
     * and listener) resolve identically.
     *
     * <p>Slot width of 100 accommodates any {@code size <= 16} (each node reserves 6 ports);
     * larger sizes must pin {@code firstGrpcPort} explicitly.
     *
     * <p>Constants must stay inside {@link SubProcessNetwork}'s {@code [30000, 40000)} candidate
     * range; {@code PORTS_PER_NODE} mirrors the private constant of the same name in that class.
     */
    private static final int SHARED_PORT_BASE = 32000;

    private static final int SHARED_PORT_SLOT = 100;
    private static final int SHARED_POOL_SLOTS = 80;
    private static final int PORTS_PER_NODE = 6;

    private record Reservation(String name, int base, int footprint) {
        int end() {
            return base + footprint;
        }
    }

    private static final Map<String, Reservation> RESERVATIONS_BY_NAME = new ConcurrentHashMap<>();

    /**
     * Where {@link Network#tssPreload tssPreload}-opted networks read cached fixtures from /
     * write fresh ones to. Resolved against the project root (gradle/test working dir varies,
     * so we walk up from CWD until we find a directory containing {@code tss-startup-assets/}).
     * The dir is gitignored; co-locates with the extracted WRAPS artifacts
     * ({@code wraps-vX.Y.Z/}, which {@code TSS_LIB_WRAPS_ARTIFACTS_PATH} points at).
     */
    private static final Path TSS_FIXTURE_CACHE_DIR = resolveTssFixtureCacheDir();

    /**
     * Where {@code BlockStreamManagerImpl} writes the network-info export. Dev defaults route
     * here (see {@link com.hedera.node.config.data.NetworkAdminConfig#diskNetworkExportFile} =
     * {@code output/network.json}). For cold-cache prep runs we override the mode to
     * {@code EVERY_BLOCK} so the snapshot is continually refreshed; the opted-in prep test
     * then settles a few seconds past WRAPS-extensible to ensure the snapshot captures a
     * resume-safe {@code HistoryProofConstruction} (hasTargetProof=true).
     */
    private static final String EXPORTED_NETWORK_RELATIVE = "output/network.json";

    /** Filename consumed by DiskStartupNetworks during subprocess genesis. */
    private static final String GENESIS_NETWORK_JSON = "genesis-network.json";

    // ── TSS-readiness gate (called from startNetworks for tssPreload-opted networks) ──
    /** Runtime log line from {@code ProofControllerImpl} when the cold WRAPS bootstrap finishes. */
    private static final Pattern WRAPS_EXTENSIBLE_PATTERN =
            Pattern.compile("History proof constructed \\(#\\d+, WRAPS-extensible\\? true\\)");
    /** Startup log line from {@code TssStartupNetworks} when a cached fixture's WRAPS state preloads. */
    private static final Pattern WRAPS_PRELOADED_PATTERN = Pattern.compile(
            "TssStartupNetworks - Initialized dev-only history startup state:.*hasChainOfTrustProof=true");
    /**
     * One-shot log line emitted by {@code BlockStreamManagerImpl.finishProofWithSignature} the
     * moment the first block proof embeds the WRAPS recursive proof — i.e. the moment captured
     * cross-network state proofs become peer-verifiable. Polled on cold bootstrap instead of
     * sleeping a fixed wall-clock duration.
     */
    private static final Pattern WRAPS_SYNC_POINT_PATTERN =
            Pattern.compile("\\[CLPR-SYNC-POINT\\] block #\\d+ is the first to embed the WRAPS recursive proof");

    /** Extracts a node fixture's {@code blsPrivateKey} for the distinct-per-node TSS-key assertion. */
    private static final Pattern BLS_PRIVATE_KEY = Pattern.compile("\"blsPrivateKey\"\\s*:\\s*\"([^\"]*)\"");

    private static final Duration WRAPS_EXTENSIBLE_TIMEOUT = Duration.ofMinutes(25);
    /**
     * Upper bound on how long we'll wait between {@code WRAPS-extensible? true} and the
     * {@code [CLPR-SYNC-POINT]} log line. Typically fires within seconds (1-2 block-times after
     * the next {@code TssBlockHashSigner} round); 3 min is paranoia padding.
     */
    private static final Duration WRAPS_SYNC_POINT_TIMEOUT = Duration.ofMinutes(3);
    /**
     * Small settle after the sync-point log to let a few WRAPS-carrying blocks be signed, so
     * subsequent {@code clprGetLedgerConfiguration} captures reliably reference a block in the
     * WRAPS-extensible run (avoids the boundary case where the first capture races the very
     * first WRAPS-carrying block and references the one before it).
     */
    private static final Duration POST_SYNC_POINT_SETTLE = Duration.ofSeconds(30);

    /**
     * Upper bound on the freeze → FREEZE_COMPLETE transition for the cold fixture-prep harvest.
     * With background traffic running during the freeze (see {@link #runFreezeForExport}), the
     * freeze block's proof gets signed in time and the platform reaches FREEZE_COMPLETE — at
     * which point the synchronous {@code ONLY_FREEZE_BLOCK} export inside {@code endRound} is
     * guaranteed to have completed and {@code output/network.json} is on disk.
     */
    private static final Duration FIXTURE_FREEZE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Per-network-instance flag set by {@link #startNetworks} whenever it completes the cold
     * WRAPS bootstrap (wait-for-extensible + 5-min settle) before the test runs. Test-layer
     * helpers (e.g. {@code HieroToHieroBase.captureConfigProof}) consult this via
     * {@link #wasTssBootstrapHandled} to skip their own settle when the extension already paid
     * for it. Cleared in {@link #afterEach} so stale entries can't leak into the next test.
     */
    private static final Set<SubProcessNetwork> TSS_BOOTSTRAP_HANDLED = ConcurrentHashMap.newKeySet();

    /**
     * @return {@code true} iff {@link #startNetworks} already completed the cold WRAPS bootstrap
     * and post-extensible settle for this network before the current test began executing.
     */
    public static boolean wasTssBootstrapHandled(@NonNull final SubProcessNetwork network) {
        return TSS_BOOTSTRAP_HANDLED.contains(network);
    }

    /**
     * Per-network-name CLPR mTLS CA material, provisioned in {@link #provisionClprMtls} when a
     * {@link Network#enableClprMtls()} network starts (or restarts warm). Keyed by name so it survives the
     * cold-path {@link #restartWithFixture} that swaps the {@link SubProcessNetwork} instance.
     * Cleared in {@link #afterEach}.
     */
    private static final Map<String, ClprMtlsCa> CLPR_MTLS_CAS = new ConcurrentHashMap<>();

    /**
     * @return the DER-encoded CA cert to advertise in {@code ClprEndpoint.tls_certificate} for the
     * given network.
     * @throws IllegalStateException if no CA was provisioned for {@code networkName}
     */
    public static byte[] clprMtlsCaDer(@NonNull final String networkName) {
        final var ca = CLPR_MTLS_CAS.get(networkName);
        if (ca == null) {
            throw new IllegalStateException("No CLPR mTLS CA provisioned for network '" + networkName
                    + "' — set enableClprMtls = true on its @Network");
        }
        return ca.caCertDer();
    }

    @Override
    public void beforeEach(@NonNull final ExtensionContext ctx) {
        findAnnotation(ctx).ifPresent(annotation -> {
            store(ctx).put(ANNOTATION_KEY, annotation);
            final var configs = annotation.value();
            final SubProcessNetwork[] networks;
            final boolean shared;
            if (!SHARED_NETWORKS.isEmpty() && allShared(configs)) {
                networks = new SubProcessNetwork[configs.length];
                for (int i = 0; i < configs.length; i++) {
                    networks[i] = SHARED_NETWORKS.get(resolveName(configs[i]));
                }
                shared = true;
                log.info(
                        "[MultiNetworkExtension] Reusing shared networks {} for test {}",
                        Arrays.stream(configs)
                                .map(MultiNetworkExtension::resolveName)
                                .toList(),
                        ctx.getDisplayName());
                // SubProcessNetwork#awaitReady memoizes its result for the lifetime of the instance and
                // is never re-armed, so it is a no-op on reuse. A shared network that an earlier suite
                // left non-ACTIVE (e.g. freeze/shutdown/restart) would
                // otherwise be handed straight to applySetupOverrides below, whose specs then fail with
                // PLATFORM_NOT_ACTIVE. Re-verify per node; this returns immediately when already ACTIVE.
                for (final var network : networks) {
                    network.nodes().forEach(node -> awaitStatus(node, SHARED_REUSE_ACTIVE_TIMEOUT, ACTIVE));
                }
            } else {
                log.info(
                        "[MultiNetworkExtension] Starting per-test networks {} for test {} (no compatible shared set)",
                        Arrays.stream(configs)
                                .map(MultiNetworkExtension::resolveName)
                                .toList(),
                        ctx.getDisplayName());
                networks = startNetworks(configs);
                shared = false;
            }
            store(ctx).put(NETWORKS_KEY, networks);
            store(ctx).put(SHARED_FLAG_KEY, shared);
            store(ctx).put(PARAM_INDEXES_KEY, networkParamIndexes(ctx, networks.length));
            // Snapshot each override's pre-test value on its network, then apply the test's value.
            // The captured map is stashed on the JUnit store so afterEach can undo exactly what
            // this test touched.
            store(ctx).put(CAPTURED_PROPS_KEY, applySetupOverrides(configs, networks));
        });
    }

    @Override
    public void afterEach(@NonNull final ExtensionContext ctx) {
        final var networks = store(ctx).remove(NETWORKS_KEY, SubProcessNetwork[].class);
        final var annotation = store(ctx).remove(ANNOTATION_KEY, MultiNetworkHapiTest.class);
        final Boolean sharedFlag = store(ctx).remove(SHARED_FLAG_KEY, Boolean.class);
        final boolean shared = sharedFlag != null && sharedFlag;
        @SuppressWarnings("unchecked")
        final var captured =
                (Map<SubProcessNetwork, Map<String, String>>) store(ctx).remove(CAPTURED_PROPS_KEY, Map.class);
        // Restore each network by replaying the pre-test value of every key this test touched.
        // Done BEFORE any termination so shared networks are clean for the next test.
        if (networks != null && captured != null) {
            restoreCapturedProperties(networks, captured);
        }
        if (networks != null && !shared) {
            // On success, harvest fresh TSS fixtures for any tssPreload-opted network that
            // doesn't yet have one cached. Run BEFORE terminating so the exported file is
            // guaranteed to be flushed; the per-block writer is idempotent.
            if (annotation != null && ctx.getExecutionException().isEmpty()) {
                final var configs = annotation.value();
                for (int i = 0; i < networks.length && i < configs.length; i++) {
                    if (configs[i].tssPreload()) {
                        cacheTssFixtureIfMissing(networks[i]);
                    }
                }
            }

            // Collect URIs before terminating so we can clean up stale channels.
            // Dead channels accumulate in the static HapiClients.channelPools and cause
            // "Connection refused" on subsequent runs because the round-robin picks them.
            final List<String> uris = new ArrayList<>();
            for (final var n : networks) {
                n.nodes().forEach(node -> uris.add(node.getHost() + ":" + node.getGrpcPort()));
            }
            for (final var n : networks) {
                TSS_BOOTSTRAP_HANDLED.remove(n);
                CLPR_MTLS_CAS.remove(n.name());
                safeTerminate(n);
            }
            HapiClients.removeChannelsFor(uris);
        }
        if (networks != null) {
            wipeClprPeerEndpointsCache(networks);
        }
        store(ctx).remove(PARAM_INDEXES_KEY);
    }

    private static boolean allShared(@NonNull final Network[] configs) {
        for (final var cfg : configs) {
            if (!SHARED_NETWORKS.containsKey(resolveName(cfg))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean supportsParameter(@NonNull final ParameterContext param, @NonNull final ExtensionContext ctx) {
        final var type = param.getParameter().getType();
        return (HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type))
                && findAnnotation(ctx).isPresent();
    }

    @Override
    public Object resolveParameter(@NonNull final ParameterContext param, @NonNull final ExtensionContext ctx) {
        final var networks = store(ctx).get(NETWORKS_KEY, SubProcessNetwork[].class);
        @SuppressWarnings("unchecked")
        final var indexes = (List<Integer>) store(ctx).get(PARAM_INDEXES_KEY, List.class);
        if (networks == null || indexes == null) {
            throw new IllegalStateException("Networks not initialized");
        }
        final int pos = indexes.indexOf(param.getIndex());
        if (pos < 0 || pos >= networks.length) {
            throw new IllegalArgumentException("Parameter index " + param.getIndex() + " not mapped to a network");
        }
        return networks[pos];
    }

    public static SubProcessNetwork[] startNetworks(@NonNull final Network[] configs) {
        final var dupes = Arrays.stream(configs)
                .collect(Collectors.groupingBy(MultiNetworkExtension::resolveName, Collectors.counting()))
                .entrySet()
                .stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!dupes.isEmpty()) throw new IllegalArgumentException("Duplicate network names: " + dupes);

        final List<SubProcessNetwork> networks = new ArrayList<>();
        for (final var cfg : configs) {
            final long shard = cfg.shard() >= 0 ? cfg.shard() : getConfigShard();
            final long realm = cfg.realm() >= 0 ? cfg.realm() : getConfigRealm();
            final var network = SubProcessNetwork.newIsolatedNetwork(
                    resolveName(cfg), cfg.size(), shard, realm, resolveFirstGrpcPort(cfg));

            // Collect setup overrides (defaults + annotation-declared + tssPreload-injected) into
            // one map so duplicates are merged predictably. Defaults are seeded first so a per-test
            // setupOverrides entry with the same key wins.
            final var overrides = new LinkedHashMap<String, String>();
            // Multi-network tests register connectors as part of setup; the prod default
            // clpr.minLockedStake (100M tinybars) requires a hbar transfer to register, which the
            // tests don't fund. Lower the threshold so simple test connectors succeed without
            // forcing every test annotation to repeat this override.
            overrides.put("clpr.minLockedStake", "100");
            overrides.put("clpr.nodeSubmitBundleMaxFee", "10000000000");
            overrides.put("clpr.verifierGasLimit", "5000000");
            for (final var o : cfg.setupOverrides()) {
                overrides.put(o.key(), o.value());
            }
            // Typed mTLS config derived from @Network attributes, set last so it wins over anything a
            // test left in setupOverrides. firstMtlsPort is a base (seedPerNodeApplicationOverrides
            // offsets it per node); enableClprMtls provisions the CA at the paths seeded here.
            if (cfg.firstMtlsPort() > 0) {
                overrides.put(CLPR_MTLS_PORT_KEY, Integer.toString(cfg.firstMtlsPort()));
            }
            if (cfg.enableClprMtls()) {
                overrides.put(CLPR_CA_CRT_PATH_KEY, CLPR_CA_CRT_PATH);
                overrides.put(CLPR_CA_KEY_PATH_KEY, CLPR_CA_KEY_PATH);
            }
            final boolean cacheHit = cfg.tssPreload() && allPerNodeFixturesPresent(resolveName(cfg), cfg.size());
            if (cfg.tssPreload() && !cacheHit) {
                // Cold-cache run: trigger a single TSS-enriched export at the freeze block
                // (after the WRAPS sync-point is reached). ONLY_FREEZE_BLOCK pays the export
                // cost once, on the freeze block, when the network is otherwise quiescent.
                overrides.putIfAbsent("networkAdmin.diskNetworkExport", "ONLY_FREEZE_BLOCK");
                overrides.putIfAbsent("networkAdmin.diskNetworkExportTss", "true");
            }
            seedPerNodeApplicationOverrides(network, overrides, cfg.size());

            // Preload TSS fixture (if cached): overwrite the default genesis-network.json that
            // initWorkingDir writes, BEFORE the subprocess JVM starts. With TSS metadata present,
            // TssStartupNetworks pre-seeds constructions and skips the ~14-min WRAPS bootstrap.
            if (cacheHit) {
                log.info("[CLPR-FIXTURE] per-node preload hit for '{}' ({} nodes)", resolveName(cfg), cfg.size());
                network.getPostInitWorkingDirActions().add(node -> {
                    try {
                        // Install THIS node's own fixture (carrying its own TSS private key).
                        final var src = resolvePerNodeCachedFixturePath(resolveName(cfg), node.getNodeId(), cfg.size());
                        final var dst = node.getExternalPath(DATA_CONFIG_DIR).resolve(GENESIS_NETWORK_JSON);
                        installFixture(src, dst);
                        // The fixture holds ports from cold-bootstrap capture time; every warm run
                        // auto-allocates a fresh random base port. Without this rewrite,
                        // Node.serviceEndpoint in state would keep the capture-time port while the
                        // live gRPC server listens on the runtime port — peer dials would miss.
                        rewriteFixturePortsToRuntime(dst, network);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(
                                "Failed to install TSS preload fixture for '" + resolveName(cfg) + "'", e);
                    }
                });
            } else if (cfg.tssPreload()) {
                log.info(
                        "[CLPR-FIXTURE] no complete per-node fixture set for '{}' ({} nodes) in {} — "
                                + "will cache after passing test",
                        resolveName(cfg),
                        cfg.size(),
                        TSS_FIXTURE_CACHE_DIR);
            }

            // Provision the per-network CLPR mTLS CA (cert/key PEMs into the working dir + DER stashed
            // for on-chain advertisement) before the node JVM starts and reads clpr.caCrtPath/caKeyPath.
            if (cfg.enableClprMtls()) {
                provisionClprMtls(cfg, network);
            }

            networks.add(network);
        }
        try {
            // Spawn each network's subprocesses and wait for ACTIVE in parallel.
            // Each task is a single blocking awaitReady poll on its own log file.
            // Failures in any task surface via Future.get() and
            // are caught by the outer catch, which then terminates every started network.
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final List<Future<Void>> futures = networks.stream()
                        .map(n -> executor.<Void>submit(() -> {
                            n.start();
                            n.awaitReady(STARTUP_TIMEOUT);
                            return null;
                        }))
                        .toList();
                for (final var f : futures) {
                    try {
                        f.get();
                    } catch (final ExecutionException e) {
                        throw new RuntimeException("Network startup failed", e.getCause());
                    }
                }
            }
            // Second pass: per-network TSS-readiness gate for tssPreload-opted networks.
            // Warm path (cached fixture preloaded at JVM start): returns ~immediately once the
            // preload log fires + first signed block arrives. Cold path (no fixture): waits up to
            // 25 min for the runtime WRAPS-extensible event (~14 min on a 1-node subprocess),
            // polls for [CLPR-SYNC-POINT] (first WRAPS-carrying block, ~seconds later), settles
            // POST_SYNC_POINT_SETTLE so a few WRAPS-carrying blocks accumulate, triggers a
            // freezeOnly to fire ONLY_FREEZE_BLOCK export of a TSS-enriched output/network.json,
            // harvests it into the cache, then restarts the network with the just-cached fixture
            // preloaded so the test runs against a warm-loaded network (byte-identical to every
            // subsequent run). Both networks bootstrap in parallel — wait wall time is whichever
            // finishes last, not the sum.
            for (int i = 0; i < networks.size(); i++) {
                final var n = networks.get(i);
                final var cfg = configs[i];
                if (!cfg.tssPreload()) continue;
                final boolean coldBootstrap = awaitTssReady(n);
                if (coldBootstrap) {
                    log.info("[CLPR-FIXTURE] '{}' cold WRAPS bootstrap complete — awaiting sync-point log", n.name());
                    awaitWrapsSyncPoint(n);
                    Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                    // Trigger the single ONLY_FREEZE_BLOCK export, then harvest the fixture.
                    log.info("[CLPR-FIXTURE] '{}' triggering freeze to flush TSS-enriched snapshot", n.name());
                    runFreezeForExport(n);
                    harvestFreshFixtureOrThrow(n);
                    // Restart the network with the just-cached per-node fixtures preloaded so the test
                    // runs against a warm-loaded network rather than a frozen one.
                    if (!allPerNodeFixturesPresent(resolveName(cfg), cfg.size())) {
                        throw new IllegalStateException(
                                "Cold-bootstrap harvest reported success but per-node fixtures are missing for '"
                                        + resolveName(cfg) + "'");
                    }
                    final var freshNetwork = restartWithFixture(cfg, n);
                    networks.set(i, freshNetwork);
                    // The fresh network is warm-preloaded — captureConfigProof will see
                    // WARM_PRELOADED and skip its own settle without needing the BOOTSTRAP_HANDLED
                    // flag, so no entry to add here.
                }
            }
            return networks.toArray(SubProcessNetwork[]::new);
        } catch (Throwable t) {
            log.warn("Failed to start networks; terminating any that started", t);
            networks.forEach(MultiNetworkExtension::safeTerminate);
            throw new RuntimeException("Failed to start multi-network set", t);
        }
    }

    public static void safeTerminate(final SubProcessNetwork n) {
        if (n == null) return;
        try {
            n.terminate();
        } catch (Throwable t) {
            log.warn("Cleanup failed for '{}'", n.name(), t);
        }
    }

    /**
     * Resolves the network name from a {@link Network} annotation, supporting both the
     * shorthand {@code @Network("ledgerA")} form (via {@link Network#value()}) and the
     * explicit {@code @Network(name = "ledgerA")} form (via {@link Network#name()}).
     *
     * <p>Callers should use this helper instead of {@link Network#name()} or
     * {@link Network#value()} directly, so the two aliases stay in sync and mismatches
     * fail loudly.
     *
     * @throws IllegalArgumentException if both {@code value} and {@code name} are set but
     *         disagree, or if neither is set
     */
    public static String resolveName(@NonNull final Network annotation) {
        final String v = annotation.value();
        final String n = annotation.name();
        if (!v.isEmpty() && !n.isEmpty() && !v.equals(n)) {
            throw new IllegalArgumentException("@MultiNetworkHapiTest.Network cannot declare both value=\"" + v
                    + "\" and name=\"" + n + "\" with different values");
        }
        if (v.isEmpty() && n.isEmpty()) {
            throw new IllegalArgumentException(
                    "@MultiNetworkHapiTest.Network requires a network name (either via value or name)");
        }
        return !v.isEmpty() ? v : n;
    }

    /**
     * Resolves the gRPC base port for a {@link Network} config and records the reservation.
     *
     * <ul>
     *   <li>Cached lookup: if this name already has a reservation, return its base.</li>
     *   <li>Explicit ({@code firstGrpcPort > 0}): reserve at the specified base — throw
     *       {@link IllegalArgumentException} if it overlaps any prior reservation, naming the
     *       conflicting network.</li>
     *   <li>Auto ({@code firstGrpcPort <= 0}): scan the shared pool for the first slot that
     *       doesn't overlap any prior reservation; reserve it.</li>
     * </ul>
     *
     * <p>Synchronized to make the "check no overlap, then record" pair atomic — otherwise two
     * concurrent lookups could both see a slot as free and both record it. Contention is
     * negligible in practice since allocation only happens at network setup.
     *
     * @throws IllegalArgumentException if an explicit port overlaps a prior reservation, or if
     *         the auto footprint doesn't fit in a slot
     * @throws IllegalStateException if the auto pool is exhausted
     */
    private static synchronized int resolveFirstGrpcPort(@NonNull final Network cfg) {
        final String name = resolveName(cfg);
        final Reservation cached = RESERVATIONS_BY_NAME.get(name);
        if (cached != null) {
            return cached.base();
        }
        final int footprint = cfg.size() * PORTS_PER_NODE;

        if (cfg.firstGrpcPort() > 0) {
            final int base = cfg.firstGrpcPort();
            final Reservation conflict = firstOverlapping(base, base + footprint);
            if (conflict != null) {
                throw new IllegalArgumentException("Explicit firstGrpcPort=" + base + " (size=" + cfg.size()
                        + ", footprint " + footprint + ") on network '" + name + "' overlaps the ["
                        + conflict.base() + ", " + conflict.end() + ") range reserved by network '"
                        + conflict.name() + "'");
            }
            RESERVATIONS_BY_NAME.put(name, new Reservation(name, base, footprint));
            return base;
        }

        if (footprint > SHARED_PORT_SLOT) {
            throw new IllegalArgumentException("Network '" + name + "' size=" + cfg.size()
                    + " exceeds shared-port slot width " + SHARED_PORT_SLOT
                    + "; pin firstGrpcPort explicitly on the annotation");
        }

        for (int slot = 0; slot < SHARED_POOL_SLOTS; slot++) {
            final int base = SHARED_PORT_BASE + slot * SHARED_PORT_SLOT;
            if (firstOverlapping(base, base + footprint) == null) {
                RESERVATIONS_BY_NAME.put(name, new Reservation(name, base, footprint));
                return base;
            }
        }
        throw new IllegalStateException("Shared-port pool exhausted while allocating for network '" + name + "'");
    }

    private static Reservation firstOverlapping(final int base, final int end) {
        for (final var r : RESERVATIONS_BY_NAME.values()) {
            if (base < r.end() && r.base() < end) {
                return r;
            }
        }
        return null;
    }

    /**
     * Polls the network's {@code hgcaa.log} for either the cold-path runtime construction event
     * or the warm-path preload event, then (warm only — cold implies it) waits for the first
     * signed block. Returns whether the path taken was a cold bootstrap (caller settles +
     * caches the fixture) or warm preload (caller does nothing else).
     *
     * @throws IllegalStateException if neither event appears within {@link #WRAPS_EXTENSIBLE_TIMEOUT}.
     */
    private static boolean awaitTssReady(@NonNull final SubProcessNetwork network) throws InterruptedException {
        final var logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        final var deadline = Instant.now().plus(WRAPS_EXTENSIBLE_TIMEOUT);
        Instant nextProgressLog = Instant.now().plus(Duration.ofMinutes(1));
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logPath)) {
                try (var lines = Files.lines(logPath)) {
                    final var matched = lines.map(line -> {
                                if (WRAPS_EXTENSIBLE_PATTERN.matcher(line).find()) return Boolean.TRUE;
                                if (WRAPS_PRELOADED_PATTERN.matcher(line).find()) return Boolean.FALSE;
                                return null;
                            })
                            .filter(Objects::nonNull)
                            .findFirst();
                    if (matched.isPresent()) {
                        // Wait for the first WRAPS-carrying block proof — implies a block was
                        // signed AND its proof embeds WRAPS material. On warm path the preload
                        // log fires before any block is produced, so this gate matters.
                        awaitWrapsSyncPoint(network);
                        return matched.get();
                    }
                } catch (final IOException ignored) {
                    // mid-rotation; retry
                }
            }
            if (Instant.now().isAfter(nextProgressLog)) {
                log.info(
                        "[CLPR-FIXTURE] still awaiting WRAPS readiness on '{}' (deadline {})",
                        network.name(),
                        deadline);
                nextProgressLog = nextProgressLog.plus(Duration.ofMinutes(1));
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Network '" + network.name()
                + "' did not produce a WRAPS-ready history proof within " + WRAPS_EXTENSIBLE_TIMEOUT);
    }

    /**
     * Polls {@code hgcaa.log} for the {@code [CLPR-SYNC-POINT]} line emitted by
     * {@code BlockStreamManagerImpl.finishProofWithSignature}. The line is guarded by an
     * {@code AtomicBoolean.compareAndSet(false, true)} in {@code BlockStreamManagerImpl}, so
     * it fires <b>exactly once per process</b>: on the first signed block whose proof embeds
     * the WRAPS recursive material.
     *
     * <p>Both bootstrap paths require this gate:
     * <ul>
     *   <li><b>Cold (TSS metadata generation)</b> — the runtime WRAPS-extensible event fires
     *       once history+TSS state is ready, but it can take an additional block or two before
     *       WRAPS material is actually carried inside a block proof.</li>
     *   <li><b>Warm (TSS preload)</b> — the preload log fires at JVM startup, before any
     *       block has been signed.</li>
     * </ul>
     *
     * <p>After this fires, captured cross-network state proofs reference WRAPS-carrying state
     * and peer ledgers' {@code NativeTssVerifier} will accept them.
     */
    private static void awaitWrapsSyncPoint(@NonNull final SubProcessNetwork network) throws InterruptedException {
        final var logPath = network.nodes()
                .getFirst()
                .metadata()
                .workingDirOrThrow()
                .resolve("output")
                .resolve("hgcaa.log");
        final var deadline = Instant.now().plus(WRAPS_SYNC_POINT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(logPath)) {
                try (var lines = Files.lines(logPath)) {
                    if (lines.anyMatch(
                            line -> WRAPS_SYNC_POINT_PATTERN.matcher(line).find())) {
                        return;
                    }
                } catch (final IOException ignored) {
                    // mid-rotation; retry
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Network '" + network.name()
                + "' did not emit [CLPR-SYNC-POINT] within " + WRAPS_SYNC_POINT_TIMEOUT
                + " — first WRAPS-carrying block proof never reached BlockStreamManagerImpl");
    }

    /**
     * Drives the network through {@code freezeOnly + waitForFrozenNetwork} so the
     * {@code ONLY_FREEZE_BLOCK} export fires once, writing a TSS-enriched
     * {@code output/network.json}.
     *
     * <p>The freeze tx alone is not enough on a 1-node subprocess: {@code Hedera.sealConsensusRound}
     * blocks the consensus thread waiting for the freeze block's proof to be signed
     * ({@code awaitFreezeRoundBlockProofsAndAcks}, default 60 s), and signing requires gossip
     * + handle traffic to keep flowing. With no inbound traffic post-freeze, the proof never
     * signs, the await times out, and the export never completes — even though the network
     * eventually transitions to FREEZE_COMPLETE much later. We fix this by running
     * {@code runBackgroundTrafficUntilFreezeComplete} alongside the freeze: a cryptoTransfer
     * firehose at 1ms/tx keeps the consensus engine producing events, the freeze block's
     * proof gets signed in time, {@code endRound}'s inline export completes synchronously, and
     * the platform reaches FREEZE_COMPLETE. (This is the same pattern Michael Tinker uses in
     * {@code LifecycleTest.upgradeToConfigVersion}.)
     */
    private static void runFreezeForExport(@NonNull final SubProcessNetwork network) {
        try {
            networkHapiTest(
                            network,
                            runBackgroundTrafficUntilFreezeComplete(),
                            freezeOnly().startingIn(2).seconds(),
                            waitForFrozenNetwork(FIXTURE_FREEZE_TIMEOUT))
                    .findFirst()
                    .orElseThrow()
                    .getExecutable()
                    .execute();
        } catch (final Throwable t) {
            throw new RuntimeException(
                    "Freeze-to-flush failed for network '" + network.name() + "': " + t.getMessage(), t);
        }
    }

    /**
     * Terminates the cold-bootstrapped network and brings up a fresh {@link SubProcessNetwork}
     * with the just-cached fixture preloaded, so the upcoming test sees a warm-loaded network
     * (the warm-path code path, byte-for-byte identical to second-and-later runs). Reuses the
     * same name + ports + shard/realm so any caller holding a port reference stays valid.
     */
    private static SubProcessNetwork restartWithFixture(
            @NonNull final Network cfg, @NonNull final SubProcessNetwork oldNetwork) {
        // Stale gRPC channels would otherwise be reused against the new process; clear them.
        final List<String> oldUris = new ArrayList<>();
        oldNetwork.nodes().forEach(node -> oldUris.add(node.getHost() + ":" + node.getGrpcPort()));
        oldNetwork.terminate();
        HapiClients.removeChannelsFor(oldUris);

        final long shard = cfg.shard() >= 0 ? cfg.shard() : getConfigShard();
        final long realm = cfg.realm() >= 0 ? cfg.realm() : getConfigRealm();
        final var fresh = SubProcessNetwork.newIsolatedNetwork(
                resolveName(cfg), cfg.size(), shard, realm, resolveFirstGrpcPort(cfg));
        // Carry over the test-declared setupOverrides (clpr.enabled, chainId, etc.). Do NOT
        // re-inject the ONLY_FREEZE_BLOCK export overrides: with a cached fixture in place this
        // is now a warm run, and the test isn't expected to issue another freeze.
        // Same defaults shape as the primary path above.
        final var overrides = new LinkedHashMap<String, String>();
        overrides.put("clpr.minLockedStake", "100");
        for (final var o : cfg.setupOverrides()) {
            overrides.put(o.key(), o.value());
        }
        // Carry the typed mTLS config across the warm restart too (see @Network.firstMtlsPort / enableClprMtls).
        if (cfg.firstMtlsPort() > 0) {
            overrides.put(CLPR_MTLS_PORT_KEY, Integer.toString(cfg.firstMtlsPort()));
        }
        if (cfg.enableClprMtls()) {
            overrides.put(CLPR_CA_CRT_PATH_KEY, CLPR_CA_CRT_PATH);
            overrides.put(CLPR_CA_KEY_PATH_KEY, CLPR_CA_KEY_PATH);
        }
        seedPerNodeApplicationOverrides(fresh, overrides, cfg.size());
        // Preload each node's OWN fixture via the same postInitWorkingDirAction the warm-cache hit uses.
        fresh.getPostInitWorkingDirActions().add(node -> {
            try {
                final var src = resolvePerNodeCachedFixturePath(resolveName(cfg), node.getNodeId(), cfg.size());
                final var dst = node.getExternalPath(DATA_CONFIG_DIR).resolve(GENESIS_NETWORK_JSON);
                installFixture(src, dst);
                rewriteFixturePortsToRuntime(dst, fresh);
            } catch (final IOException e) {
                throw new UncheckedIOException(
                        "Failed to install just-cached TSS fixture into restarted '" + resolveName(cfg) + "'", e);
            }
        });
        // Re-provision the CLPR mTLS CA on the fresh node: the old instance's post-init action is
        // gone, so without this the restarted (actually-tested) node would come up without a CA and
        // silently fall back to plaintext.
        if (cfg.enableClprMtls()) {
            provisionClprMtls(cfg, fresh);
        }
        log.info(
                "[CLPR-FIXTURE] '{}' restarting with just-cached per-node fixtures preloaded ({} nodes)",
                resolveName(cfg),
                cfg.size());
        fresh.start();
        fresh.awaitReady(STARTUP_TIMEOUT);
        return fresh;
    }

    /**
     * Seeds every node's {@code application.properties} overrides from {@code overrides}, but assigns
     * the node-local {@code clpr.mtlsPort} a <b>per-node</b> value ({@code base + nodeId}). Subprocess
     * nodes all share {@code 127.0.0.1}, so a single fixed {@code mtlsPort} would collide when more
     * than one node tries to bind its mTLS listener; {@code clpr.mtlsPort} is a {@code @NodeProperty}
     * (node-local, not consensus state), so a distinct per-node value is legitimate. All other keys are
     * applied uniformly.
     */
    private static void seedPerNodeApplicationOverrides(
            @NonNull final SubProcessNetwork network, @NonNull final Map<String, String> overrides, final int size) {
        if (overrides.isEmpty()) {
            return;
        }
        for (long id = 0; id < size; id++) {
            final long nodeId = id;
            final List<String> flat = new ArrayList<>();
            overrides.forEach((k, v) -> {
                flat.add(k);
                if (CLPR_MTLS_PORT_KEY.equals(k)) {
                    flat.add(Integer.toString(Integer.parseInt(v.trim()) + (int) nodeId));
                } else {
                    flat.add(v);
                }
            });
            network.getApplicationPropertyOverrides().put(id, List.copyOf(flat));
        }
        // Make the per-node mtlsPort offset visible: the single base expands to base..base+size-1.
        final var mtlsBase = overrides.get(CLPR_MTLS_PORT_KEY);
        if (mtlsBase != null) {
            final int base = Integer.parseInt(mtlsBase.trim());
            final var assignments = new StringBuilder();
            for (int id = 0; id < size; id++) {
                assignments
                        .append(id == 0 ? "" : ", ")
                        .append("node")
                        .append(id)
                        .append('=')
                        .append(base + id);
            }
            log.info("[CLPR-MTLS] '{}' per-node clpr.mtlsPort: {}", network.name(), assignments);
        }
    }

    /**
     * Generates a per-network ECDSA P-384 CLPR CA and registers a post-init working-dir action that
     * drops its cert + PKCS#8 key PEMs into each node's working dir at {@code data/clpr/ca.crt} /
     * {@code data/clpr/ca.key} — the relative paths the suite advertises via {@code clpr.caCrtPath} /
     * {@code clpr.caKeyPath}, resolved against the node's CWD (which is its working dir). The CA cert
     * DER is stashed by name so the suite can advertise it on-chain via {@link #clprMtlsCaDer(String)}.
     *
     * <p>Called both from the primary start loop and from {@link #restartWithFixture} so a cold-path
     * restart re-provisions the fresh node (and refreshes the stashed CA to match the files on disk).
     */
    private static void provisionClprMtls(@NonNull final Network cfg, @NonNull final SubProcessNetwork network) {
        final ClprMtlsCa ca;
        try {
            ca = new ClprMtlsCa("clpr-ca-" + cfg.name());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate CLPR mTLS CA for '" + cfg.name() + "'", e);
        }
        CLPR_MTLS_CAS.put(cfg.name(), ca);
        network.getPostInitWorkingDirActions().add(node -> {
            try {
                final var workingDir = node.getExternalPath(WORKING_DIR);
                final var crtPath = workingDir.resolve(CLPR_CA_CRT_PATH);
                final var keyPath = workingDir.resolve(CLPR_CA_KEY_PATH);
                Files.createDirectories(crtPath.getParent());
                ca.writePem(crtPath, keyPath);
            } catch (final Exception e) {
                throw new UncheckedIOException(
                        new IOException("Failed to write CLPR mTLS CA PEMs for '" + cfg.name() + "'", e));
            }
        });
    }

    /**
     * Mandatory variant of {@link #cacheTssFixtureIfMissing} used in the cold-bootstrap path
     * where {@link #runFreezeForExport} has just successfully fired and the source file MUST
     * exist. Throws on any failure so the test surfaces a clear error instead of silently
     * proceeding to {@link #restartWithFixture} (which would then fail with a generic
     * {@code NoSuchFileException} for the missing cache file).
     */
    private static void harvestFreshFixtureOrThrow(@NonNull final SubProcessNetwork network) {
        try {
            Files.createDirectories(TSS_FIXTURE_CACHE_DIR);
            final int size = network.nodes().size();
            // Harvest each node's OWN export: node<i>/output/network.json carries node<i>'s dev TSS
            // private key. All nodes export at the same freeze block, so the public/aggregation material
            // is identical across them; only the embedded private key differs. Installing each node's
            // fixture back to that node is what makes a warm multi-node start work.
            for (final var node : network.nodes()) {
                final long nodeId = node.getNodeId();
                final var src = node.getExternalPath(WORKING_DIR).resolve(EXPORTED_NETWORK_RELATIVE);
                if (!Files.exists(src)) {
                    throw new IllegalStateException("Cold-bootstrap harvest expected freeze export at " + src + " (node"
                            + nodeId + ") — not found");
                }
                final var dst = TSS_FIXTURE_CACHE_DIR.resolve(perNodeFixtureBase(network.name(), nodeId, size) + ".gz");
                gzipTo(src, dst);
                log.info(
                        "[CLPR-FIXTURE] harvested fresh '{}' node{} fixture to {} ({} bytes, from {} bytes raw)",
                        network.name(),
                        nodeId,
                        dst,
                        Files.size(dst),
                        Files.size(src));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to harvest fresh TSS fixture for '" + network.name() + "'", e);
        }
    }

    /**
     * Copy the network's exported-network.json (the TSS-enriched snapshot
     * {@code BlockStreamManagerImpl} writes when {@code networkAdmin.diskNetworkExport*}
     * are enabled) into the persistent fixture cache, but only if no fixture exists yet.
     * First-writer-wins so a flaky later test can't corrupt a good fixture.
     */
    private static void cacheTssFixtureIfMissing(@NonNull final SubProcessNetwork network) {
        try {
            // First-writer-wins: if every node already has a cached per-node fixture, leave them be.
            if (allPerNodeFixturesPresent(network.name(), network.nodes().size())) {
                return;
            }
            Files.createDirectories(TSS_FIXTURE_CACHE_DIR);
            final int size = network.nodes().size();
            // Harvest each node's own export (node<i>/output/network.json), which carries node<i>'s TSS
            // private key. Written by BlockStreamManagerImpl on the freeze block — if absent, the
            // opted-in test didn't trigger a freeze before terminating; warn and skip.
            for (final var node : network.nodes()) {
                final long nodeId = node.getNodeId();
                if (resolvePerNodeCachedFixturePath(network.name(), nodeId, size) != null) {
                    continue;
                }
                final var src = node.getExternalPath(WORKING_DIR).resolve(EXPORTED_NETWORK_RELATIVE);
                if (!Files.exists(src)) {
                    log.warn(
                            "[CLPR-FIXTURE] '{}' node{} passed but no {} — opted-in test should run "
                                    + "freezeOnly()+waitForFrozenNetwork() as its last step",
                            network.name(),
                            nodeId,
                            src);
                    return;
                }
                final var dst = TSS_FIXTURE_CACHE_DIR.resolve(perNodeFixtureBase(network.name(), nodeId, size) + ".gz");
                gzipTo(src, dst);
                log.info(
                        "[CLPR-FIXTURE] cached '{}' node{} fixture to {} ({} bytes, from {} bytes raw)",
                        network.name(),
                        nodeId,
                        dst,
                        Files.size(dst),
                        Files.size(src));
            }
        } catch (final IOException e) {
            log.warn("[CLPR-FIXTURE] failed to cache fixture for '{}': {}", network.name(), e.getMessage());
        }
    }

    /**
     * Fixture base filename for one node. <b>Size-1</b> networks keep the legacy single-file name
     * {@code <network>-genesis-network.json} (backward-compatible with every committed size-1 fixture);
     * <b>multi-node</b> networks use {@code <network>-node<id>-genesis-network.json}, because each
     * node's fixture carries only <em>its own</em> TSS private key and one shared file can't warm-start
     * the others.
     */
    private static String perNodeFixtureBase(@NonNull final String networkName, final long nodeId, final int size) {
        return size == 1
                ? networkName + "-" + GENESIS_NETWORK_JSON
                : networkName + "-node" + nodeId + "-" + GENESIS_NETWORK_JSON;
    }

    /**
     * Locate the cached fixture for a single node of {@code networkName} ({@code size} nodes total),
     * preferring the gzipped form (the committed CI source-of-truth) over a stale uncompressed local
     * copy. Returns {@code null} if neither exists.
     *
     * <p>For multi-node networks the fixtures are <b>per node</b>: each carries only that node's TSS
     * private key (harvested per node — see {@link #harvestFreshFixtureOrThrow}). A single shared
     * fixture cannot warm-start a size&gt;1 network, because the harvesting node cannot know the other
     * nodes' secrets, so those nodes would find no usable private key and generate non-matching ones
     * (invalid hinTS signatures → no block proof completes). Size-1 networks are unaffected and keep
     * the legacy single-file name.
     */
    @Nullable
    private static Path resolvePerNodeCachedFixturePath(
            @NonNull final String networkName, final long nodeId, final int size) {
        final var base = perNodeFixtureBase(networkName, nodeId, size);
        final var gz = TSS_FIXTURE_CACHE_DIR.resolve(base + ".gz");
        if (Files.exists(gz)) return gz;
        final var raw = TSS_FIXTURE_CACHE_DIR.resolve(base);
        return Files.exists(raw) ? raw : null;
    }

    /** True iff every node of a {@code size}-node {@code networkName} has a cached fixture. */
    private static boolean allPerNodeFixturesPresent(@NonNull final String networkName, final int size) {
        for (long id = 0; id < size; id++) {
            if (resolvePerNodeCachedFixturePath(networkName, id, size) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Test hook: verifies the per-node TSS fixtures for {@code networkName} each carry exactly one
     * {@code blsPrivateKey} and that those keys are pairwise <b>distinct</b> — proving the per-node
     * harvest captured each node's own secret rather than a single shared node0 key (which is what
     * silently broke multi-node warm start). Throws {@link AssertionError} otherwise. Call after the
     * network is up (fixtures are harvested during network start).
     */
    public static void assertPerNodeFixturesHaveDistinctKeys(@NonNull final String networkName, final int size) {
        final Set<String> keys = new HashSet<>();
        for (long id = 0; id < size; id++) {
            final var path = resolvePerNodeCachedFixturePath(networkName, id, size);
            if (path == null) {
                throw new AssertionError("Missing per-node TSS fixture for '" + networkName + "' node" + id);
            }
            final String key = extractSingleBlsPrivateKey(readFixtureJson(path), networkName, id);
            if (!keys.add(key)) {
                throw new AssertionError("Per-node TSS fixtures for '" + networkName + "' share a blsPrivateKey — "
                        + "node" + id + " duplicates another node's key (harvest is not per-node)");
            }
        }
        log.info("[CLPR-FIXTURE] verified {} distinct per-node blsPrivateKeys for '{}'", size, networkName);
    }

    private static String readFixtureJson(@NonNull final Path path) {
        try {
            if (path.getFileName().toString().endsWith(".gz")) {
                try (var in = new GZIPInputStream(Files.newInputStream(path))) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return Files.readString(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read TSS fixture " + path, e);
        }
    }

    private static String extractSingleBlsPrivateKey(
            @NonNull final String json, @NonNull final String networkName, final long nodeId) {
        final var m = BLS_PRIVATE_KEY.matcher(json);
        if (!m.find()) {
            throw new AssertionError("No blsPrivateKey in '" + networkName + "' node" + nodeId + " fixture");
        }
        final String key = m.group(1);
        if (key.isEmpty()) {
            throw new AssertionError("Empty blsPrivateKey in '" + networkName + "' node" + nodeId + " fixture");
        }
        if (m.find()) {
            throw new AssertionError("Multiple blsPrivateKey entries in '" + networkName + "' node" + nodeId
                    + " fixture — expected exactly one (this node's own key)");
        }
        return key;
    }

    /**
     * Install a cached fixture at {@code dst}, decompressing on the fly if {@code src} is gzipped.
     * The subprocess's DiskStartupNetworks always reads plain JSON, so the destination is always
     * uncompressed regardless of the cached form.
     */
    private static void installFixture(@NonNull final Path src, @NonNull final Path dst) throws IOException {
        if (src.getFileName().toString().endsWith(".gz")) {
            try (var in = new GZIPInputStream(Files.newInputStream(src))) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Rewrite the port fields of every {@code nodeMetadata} entry in a just-installed fixture at
     * {@code fixturePath} so they match the runtime ports of {@code subNet} (random-allocated per
     * run). Keeps every other field — TSS keys, rosters, weights, gossip CA cert — intact, so the
     * whole point of the fixture (skip the ~14-min WRAPS bootstrap) is preserved.
     *
     * <p>Patches, per node: {@code node.serviceEndpoint[0]}, {@code node.gossipEndpoint[0..1]},
     * and {@code rosterEntry.gossipEndpoint[0..1]}. IP address is reset to loopback (fixtures are
     * captured on loopback and warm runs always come up on loopback too).
     */
    private static void rewriteFixturePortsToRuntime(
            @NonNull final Path fixturePath, @NonNull final SubProcessNetwork subNet) throws IOException {
        final var loaded = DiskStartupNetworks.loadNetworkFrom(fixturePath)
                .orElseThrow(() ->
                        new IllegalStateException("Failed to reload freshly-installed fixture at " + fixturePath));
        final Map<Long, HederaNode> byId = new LinkedHashMap<>();
        for (final var n : subNet.nodes()) {
            byId.put(n.getNodeId(), n);
        }
        final var localhost = Bytes.wrap(new byte[] {127, 0, 0, 1});
        final var patched = loaded.copyBuilder()
                .nodeMetadata(loaded.nodeMetadata().stream()
                        .map(md -> {
                            final long id = md.rosterEntryOrThrow().nodeId();
                            final var live = byId.get(id);
                            if (live == null) {
                                throw new IllegalStateException("Fixture references unknown nodeId=" + id
                                        + " (runtime network '" + subNet.name() + "' has "
                                        + subNet.nodes().size() + " nodes)");
                            }
                            final var meta = live.metadata();
                            final var internal = ServiceEndpoint.newBuilder()
                                    .ipAddressV4(localhost)
                                    .port(meta.internalGossipPort())
                                    .build();
                            final var external = ServiceEndpoint.newBuilder()
                                    .ipAddressV4(localhost)
                                    .port(meta.externalGossipPort())
                                    .build();
                            final var service = ServiceEndpoint.newBuilder()
                                    .ipAddressV4(localhost)
                                    .port(meta.grpcPort())
                                    .build();
                            return md.copyBuilder()
                                    .rosterEntry(md.rosterEntryOrThrow()
                                            .copyBuilder()
                                            .gossipEndpoint(List.of(internal, external))
                                            .build())
                                    .node(md.nodeOrThrow()
                                            .copyBuilder()
                                            .gossipEndpoint(List.of(internal, external))
                                            .serviceEndpoint(List.of(service))
                                            .build())
                                    .build();
                        })
                        .toList())
                .build();
        // FQN: `Network` is already imported as the `MultiNetworkHapiTest.Network` annotation,
        // so the internal-network PBJ type must be spelled out inline for the JSON codec.
        Files.writeString(fixturePath, com.hedera.node.internal.network.Network.JSON.toJSON(patched));
    }

    /**
     * Stream-gzip {@code src} into {@code dst}. Used by the cold-bootstrap harvest so the
     * harvested fixture lands in the cache directory already in committable form — no manual
     * {@code gzip} step needed before {@code git add}.
     */
    private static void gzipTo(@NonNull final Path src, @NonNull final Path dst) throws IOException {
        try (var in = Files.newInputStream(src);
                var out = new GZIPOutputStream(Files.newOutputStream(dst))) {
            in.transferTo(out);
        }
    }

    /**
     * Walk up from the test JVM's working dir to find {@code tss-startup-assets/} — the
     * developer-local dir holding warm-cache fixtures alongside the extracted WRAPS proving
     * artifacts. Canonical location is {@code hedera-node/test-clients/tss-startup-assets/}
     * (it's used only by HAPI tests). The walk-up handles both:
     *
     * <ul>
     *   <li>{@code :test-clients:testSubprocess} — CWD is the test-clients project dir, so
     *       {@code tss-startup-assets/} is found on iteration 0;</li>
     *   <li>any other invocation — walks up until it finds either {@code tss-startup-assets/}
     *       directly or the canonical {@code hedera-node/test-clients/tss-startup-assets/}
     *       path under a parent.</li>
     * </ul>
     *
     * <p>Falls back to a plain relative path if neither is found; the cache then won't
     * persist across runs, which is harmless (just slow).
     */
    private static Path resolveTssFixtureCacheDir() {
        var dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6; i++) {
            if (Files.isDirectory(dir.resolve("tss-startup-assets"))) {
                return dir.resolve("tss-startup-assets");
            }
            final var canonical =
                    dir.resolve("hedera-node").resolve("test-clients").resolve("tss-startup-assets");
            if (Files.isDirectory(canonical)) {
                return canonical;
            }
            final var parent = dir.getParent();
            if (parent == null) break;
            dir = parent;
        }
        return Paths.get("tss-startup-assets");
    }

    /**
     * Deletes {@code data/clpr/peer-endpoints.json} on every node of every network. Safe for both
     * per-test (terminated) networks and live shared networks.
     */
    private static void wipeClprPeerEndpointsCache(@NonNull final SubProcessNetwork[] networks) {
        for (final var n : networks) {
            if (n == null) continue;
            for (final var node : n.nodes()) {
                final Path path;
                try {
                    path = node.metadata().workingDirOrThrow().resolve("data/clpr/peer-endpoints.json");
                } catch (final Throwable t) {
                    log.warn(
                            "[CLPR-CLEANUP] could not resolve peer-endpoints path for '{}': {}",
                            n.name(),
                            t.toString());
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException e) {
                    log.warn("[CLPR-CLEANUP] failed to delete {} for '{}': {}", path, n.name(), e.getMessage());
                }
            }
        }
    }

    /**
     * Snapshots the pre-test value of every key this test declares in
     * {@link Network#setupOverrides() setupOverrides}, then applies the test's values via
     * {@code fileUpdate(APP_PROPERTIES)}. The returned map — {@code network -> (key -> prior-value)} —
     * lets {@link #restoreCapturedProperties} undo exactly what this test touched, whether or not
     * the listener seeded that key at boot.
     *
     * <p>Mirrors LeakyHapiTest's {@code remembering(...)} + {@code overridingAllOf(...)} pattern:
     * capture-then-mutate on the way in, replay-captured on the way out. Errors per override are
     * logged and swallowed so a misconfigured key can't mask the test's own failure mode.
     */
    private static Map<SubProcessNetwork, Map<String, String>> applySetupOverrides(
            @NonNull final Network[] configs, @NonNull final SubProcessNetwork[] networks) {
        final Map<SubProcessNetwork, Map<String, String>> captured = new LinkedHashMap<>();
        for (int i = 0; i < configs.length && i < networks.length; i++) {
            final var network = networks[i];
            final var overrides = configs[i].setupOverrides();
            if (overrides.length == 0) continue;
            final List<String> keys = Arrays.stream(overrides)
                    .map(ConfigOverride::key)
                    .filter(k -> !STARTUP_ONLY_NODE_KEYS.contains(k))
                    .distinct()
                    .toList();
            final Map<String, String> prior = new LinkedHashMap<>();
            snapshotPropertiesOn(network, prior, keys);
            captured.put(network, prior);
            for (final var override : overrides) {
                // Node-local, STARTUP-only config (read once at JVM boot from application.properties,
                // where seedPerNodeApplicationOverrides seeds it PER NODE). Applying it via the 0.0.121
                // network override does nothing at runtime and — worse — the network value would shadow a
                // later per-node application.properties change across a restart (e.g. an mtlsPort rotation).
                if (STARTUP_ONLY_NODE_KEYS.contains(override.key())) {
                    continue;
                }
                log.info(
                        "[MultiNetworkExtension] applying test override {}={} on '{}' (was {})",
                        override.key(),
                        override.value(),
                        network.name(),
                        prior.getOrDefault(override.key(), "<unset>"));
                applyPropertyTo(network, override.key(), override.value());
            }
        }
        return captured;
    }

    /**
     * Restores each network's overridden keys to the values captured by
     * {@link #applySetupOverrides} at test start. Called at the start of {@link #afterEach},
     * before any termination, so shared networks are clean for the next test.
     *
     * <p>Only the exact keys this test touched are replayed — so a key the test introduced but
     * the listener never seeded still gets reverted (to its network default), and a listener-seeded
     * key the test didn't touch is left alone (no wasted {@code fileUpdate}s).
     */
    private static void restoreCapturedProperties(
            @NonNull final SubProcessNetwork[] networks,
            @NonNull final Map<SubProcessNetwork, Map<String, String>> captured) {
        for (final var network : networks) {
            if (network == null) continue;
            final var prior = captured.get(network);
            if (prior == null || prior.isEmpty()) continue;
            for (final var e : prior.entrySet()) {
                log.info("[MultiNetworkExtension] restoring {}={} on '{}'", e.getKey(), e.getValue(), network.name());
                applyPropertyTo(network, e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Runs a single-op {@code networkHapiTest(network, remembering(into, keys))} so the pre-test
     * value of each key in {@code keys} lands in {@code into}. Falls back to the network's
     * startup properties for keys not yet present in {@code APP_PROPERTIES}. Errors are logged
     * and not rethrown (afterEach will simply skip restoring what wasn't captured).
     */
    private static void snapshotPropertiesOn(
            @NonNull final SubProcessNetwork network,
            @NonNull final Map<String, String> into,
            @NonNull final List<String> keys) {
        try {
            networkHapiTest(network, remembering(into, keys))
                    .findFirst()
                    .orElseThrow()
                    .getExecutable()
                    .execute();
        } catch (final Throwable t) {
            log.error("[MultiNetworkExtension] failed to snapshot properties {} on '{}': {}", keys, network.name(), t);
        }
    }

    /**
     * Runs a single-op {@code networkHapiTest(network, overriding(key, value))} HapiSpec directly
     * so the property update is applied through the same {@code fileUpdate(APP_PROPERTIES)} path
     * that the rest of the test infra uses. Errors are logged and not rethrown.
     */
    private static void applyPropertyTo(
            @NonNull final SubProcessNetwork network, @NonNull final String key, @NonNull final String value) {
        try {
            networkHapiTest(network, overriding(key, value))
                    .findFirst()
                    .orElseThrow()
                    .getExecutable()
                    .execute();
        } catch (final Throwable t) {
            log.error(
                    "[MultiNetworkExtension] failed to apply property {}={} on '{}': {}",
                    key,
                    value,
                    network.name(),
                    t);
        }
    }

    private List<Integer> networkParamIndexes(@NonNull final ExtensionContext ctx, final int expected) {
        final var params = ctx.getRequiredTestMethod().getParameters();
        final List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            final var type = params[i].getType();
            if (HederaNetwork.class.isAssignableFrom(type) || SubProcessNetwork.class.isAssignableFrom(type)) {
                indexes.add(i);
            }
        }
        if (indexes.size() != expected) {
            throw new IllegalStateException("Expected " + expected + " network parameters, found " + indexes.size());
        }
        return indexes;
    }

    private Optional<MultiNetworkHapiTest> findAnnotation(@NonNull final ExtensionContext ctx) {
        return ctx.getTestMethod()
                .map(m -> m.getAnnotation(MultiNetworkHapiTest.class))
                .or(() -> ctx.getTestClass().map(c -> c.getAnnotation(MultiNetworkHapiTest.class)));
    }

    private ExtensionContext.Store store(@NonNull final ExtensionContext ctx) {
        return ctx.getStore(NAMESPACE);
    }
}
