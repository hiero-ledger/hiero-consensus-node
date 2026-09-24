// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.ClprTssFixtureHarvester.resolveCachedFixturePath;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork.ConfigVersionSource.PER_NETWORK_CONFIG_VERSION;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.remembering;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.subprocess.ClprTssFixtureHarvester;
import com.hedera.services.bdd.junit.hedera.subprocess.ClprWrapsProvingKeyInstaller;
import com.hedera.services.bdd.junit.hedera.subprocess.MultiNetworkLifecycleTest;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
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
    private static final String NETWORK_GROUP_KEY = "networkGroupKey";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
    /** How long to wait for an already-started shared network to be ACTIVE again before reusing it. */
    private static final Duration SHARED_REUSE_ACTIVE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Networks pre-started once per test plan by {@code SharedMultiNetworkLauncherSessionListener}
     * and reused across every {@link MultiNetworkHapiTest} method whose declared network names
     * resolve here. Empty unless the launcher-session listener populated it.
     */
    public static final Map<String, SubProcessNetwork> SHARED_NETWORKS = new ConcurrentHashMap<>();

    /**
     * Canonical {@code @Network} config per shared-network name, recorded up front by
     * {@code SharedMultiNetworkLauncherSessionListener} (which also reserves each one's ports). A network
     * whose name is a key here is SHARED: it is started lazily on first demand (see
     * {@link #getOrStartShared}) and kept warm in {@link #SHARED_NETWORKS} for reuse. A network whose name
     * is absent is started per-test and torn down in {@code afterEach}. Empty unless the launcher-session
     * listener populated it.
     */
    public static final Map<String, Network> DECLARED_CONFIGS = new ConcurrentHashMap<>();

    /** Guards the one-time driver-log reconfigure done on the first lazily-started shared network. */
    private static final AtomicBoolean SHARED_LOGGING_CONFIGURED = new AtomicBoolean(false);

    /** System property keys consumed by {@code log4j2-test-client.xml}'s {@code RollingFile} appender. */
    private static final String TEST_CLIENT_LOG_FILE = "hapi.test.clients.log.file";

    private static final String TEST_CLIENT_LOG_FILE_PATTERN = "hapi.test.clients.log.filePattern";

    /** Sibling dir (of the per-network {@code <scope>-test/} dirs) for the multi-network driver log. */
    private static final String MULTINETWORK_LOG_DIR = "multinetwork-test-clients";

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

    /** Set once we have seeded {@link #RESERVATIONS_BY_NAME} from the committed fixtures (see below). */
    private static boolean fixturePortReservationsSeeded = false;

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
            // Per-network serialization is done by JUnit via ClprNetworkLocksProvider (a @ResourceLock
            // provider on @MultiNetworkHapiTest): same-network tests take turns (READ overlaps, a @Leaky
            // WRITE runs alone), different groups run in parallel. So there is no lock to take here.
            final SubProcessNetwork[] networks;
            final boolean shared;
            if (allDeclaredShared(configs)) {
                // Lazy start: get each shared network, starting it on first demand and
                // reusing it thereafter. Start under the canonical config the launcher reserved ports
                // for, not this test's copy, so every test on the name hits the same subprocess.
                //
                // Gate on the node budget FIRST: enterNetworkGroup blocks until this network group's nodes
                // fit the MAX_NODES budget (a group that doesn't fit waits here until enough nodes free up),
                // so no more than the node budget boots/runs at once. Only then do we lazily boot the networks.
                final String networkGroupKey = MultiNetworkGroupBudget.networkGroupKey(configs);
                store(ctx).put(NETWORK_GROUP_KEY, networkGroupKey);
                MultiNetworkGroupBudget.enterNetworkGroup(networkGroupKey, MultiNetworkGroupBudget.nodeWeight(configs));
                // Boot the group's networks in PARALLEL (one virtual thread per network) rather than one
                // after another: getOrStartShared is start-once per name (its own per-name lock), and
                // distinct names boot independently, so a group's start is the slowest single network's
                // time, not the sum. Lazy start is preserved — this still runs on first demand in
                // beforeEach; only the within-group boot is now concurrent. Warm reuse (cache hit) returns
                // ~immediately, so already-started networks cost nothing here.
                networks = new SubProcessNetwork[configs.length];
                try (final var startExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final List<Future<?>> startFutures = new ArrayList<>();
                    for (int i = 0; i < configs.length; i++) {
                        final int idx = i;
                        startFutures.add(startExecutor.submit(() ->
                                networks[idx] = getOrStartShared(DECLARED_CONFIGS.get(resolveName(configs[idx])))));
                    }
                    for (final var f : startFutures) {
                        try {
                            f.get();
                        } catch (final ExecutionException e) {
                            final var cause = e.getCause();
                            throw (cause instanceof RuntimeException re)
                                    ? re
                                    : new RuntimeException("Shared network startup failed", cause);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while starting shared networks", e);
                        }
                    }
                }
                shared = true;
                log.info(
                        "[MultiNetworkExtension] Using shared networks {} for test {}",
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
        store(ctx).remove(ANNOTATION_KEY, MultiNetworkHapiTest.class);
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
        // Decide whether to tear these networks down now:
        //  - per-test (non-shared) networks: always, after their single test;
        //  - shared network groups: only when this was the network group's LAST test, so the network group
        // stays warm
        //    across all its tests and is torn down exactly once, then its budget slot is freed.
        final String networkGroupKey = store(ctx).remove(NETWORK_GROUP_KEY, String.class);
        final boolean networkGroupComplete =
                shared && networkGroupKey != null && MultiNetworkGroupBudget.leaveNetworkGroupIsLast(networkGroupKey);
        if (networks != null && (!shared || networkGroupComplete)) {
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
                if (shared) {
                    // Drop from the warm cache so a later demand (should not happen once a network group is
                    // complete) would lazily re-start rather than reuse a terminated network.
                    SHARED_NETWORKS.remove(n.name());
                }
            }
            HapiClients.removeChannelsFor(uris);
            if (networkGroupComplete) {
                // Networks are down; free this group's node permits so a waiting network group can be admitted.
                MultiNetworkGroupBudget.releaseSlot(networkGroupKey);
            }
        }
        if (networks != null) {
            wipeClprPeerEndpointsCache(networks);
        }
        store(ctx).remove(PARAM_INDEXES_KEY);
    }

    /**
     * True when every config names a network the launcher declared as shared (in {@link #DECLARED_CONFIGS}),
     * so it should be lazily started once and reused rather than started per-test. Independent of whether
     * the networks have actually booted yet.
     */
    private static boolean allDeclaredShared(@NonNull final Network[] configs) {
        if (DECLARED_CONFIGS.isEmpty()) {
            return false;
        }
        for (final var cfg : configs) {
            if (!DECLARED_CONFIGS.containsKey(resolveName(cfg))) {
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

    /**
     * Reserves the gRPC/mTLS port window for each declared network WITHOUT starting any nodes. Called
     * once up front by the launcher-session listener (with explicit-port networks sorted first) so that
     * shared networks starting lazily and out of order can't land on each other's ports.
     */
    public static void reservePorts(@NonNull final Network[] configs) {
        ensureFixturePortReservations();
        for (final var cfg : configs) {
            resolveFirstGrpcPort(cfg);
        }
    }

    /**
     * Returns the shared subprocess network for {@code canonical}, starting it on first demand and
     * caching it in {@link #SHARED_NETWORKS} for reuse. The caller holds this
     * network's scheduler WRITE lock, so no two threads race to start the same name; distinct names may
     * start concurrently. The first network to boot reconfigures the shared driver log.
     */
    public static SubProcessNetwork getOrStartShared(@NonNull final Network canonical) {
        final String name = resolveName(canonical);
        final var existing = SHARED_NETWORKS.get(name);
        if (existing != null) {
            return existing;
        }
        // Under concurrent (READ) methods, several tests on the same network can reach here at once;
        // a per-name lock (double-checked) guarantees the network is started exactly once and the rest
        // reuse it. Distinct names lock independently, so different groups still boot in parallel.
        synchronized (START_LOCKS.computeIfAbsent(name, k -> new Object())) {
            final var started = SHARED_NETWORKS.get(name);
            if (started != null) {
                return started;
            }
            log.info("[MultiNetworkExtension] Lazily starting shared network '{}'", name);
            final var network = startNetworks(new Network[] {canonical})[0];
            if (SHARED_LOGGING_CONFIGURED.compareAndSet(false, true)) {
                reconfigureSharedSubProcessLogging(network);
            }
            SHARED_NETWORKS.put(name, network);
            return network;
        }
    }

    /** Per-network-name monitors so concurrent readers start a shared network exactly once. */
    private static final Map<String, Object> START_LOCKS = new ConcurrentHashMap<>();

    /**
     * Routes the multi-network driver log to a sibling of each network's working dir (e.g.
     * {@code build/multinetwork-test-clients/}), not inside any one network's node0 output. Called once,
     * on the first lazily-started shared network. The first node's working dir is
     * {@code build/<scope>-test/node0}; two parents up is the gradle build root (subtask-name nesting is
     * disabled for MULTINETWORK in build.gradle.kts, so the depth is fixed).
     */
    private static void reconfigureSharedSubProcessLogging(@NonNull final SubProcessNetwork network) {
        final var workingDir = network.nodes()
                .getFirst()
                .getExternalPath(WORKING_DIR)
                .toAbsolutePath()
                .normalize();
        final Path outputDir;
        try {
            outputDir = workingDir.getParent().getParent().resolve(MULTINETWORK_LOG_DIR);
            Files.createDirectories(outputDir);
        } catch (final RuntimeException | IOException e) {
            log.warn("Could not resolve multi-network test-client log dir from '{}'", workingDir, e);
            return;
        }
        System.setProperty(
                TEST_CLIENT_LOG_FILE, outputDir.resolve("test-clients.log").toString());
        System.setProperty(
                TEST_CLIENT_LOG_FILE_PATTERN,
                outputDir.resolve("test-clients-%d{yyyy-MM-dd}-%i.log").toString());
        Configurator.reconfigure();
        log.info("Configured shared multi-network test-client logging under {}", outputDir);
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

        // Reserve the ports every committed fixture was captured with, so a network that has a fixture
        // reproduces those exact ports on warm start (no JSON patching needed) and a brand-new network
        // is allocated a range that avoids them.
        ensureFixturePortReservations();

        final List<SubProcessNetwork> networks = new ArrayList<>();
        for (final var cfg : configs) {
            final long shard = cfg.shard() >= 0 ? cfg.shard() : getConfigShard();
            final long realm = cfg.realm() >= 0 ? cfg.realm() : getConfigRealm();
            final var network = SubProcessNetwork.newIsolatedNetwork(
                    resolveName(cfg), cfg.size(), shard, realm, resolveFirstGrpcPort(cfg));
            // Give this network its own config-version counter (starting at 0) before it starts, so a
            // concurrent network's config-version upgrade can't leak into this network's genesis/restart.
            MultiNetworkLifecycleTest.register(resolveName(cfg));

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
            final boolean cacheHit = cfg.tssPreload() && ClprTssFixtureHarvester.fixturePresent(resolveName(cfg));
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
                        // Install the network's merged fixture into every node; at genesis each node
                        // loads only its own key (filtered by selfNodeId), so one file warm-starts all.
                        final var src = resolveCachedFixturePath(resolveName(cfg));
                        final var dst = node.getExternalPath(DATA_CONFIG_DIR)
                                .resolve(ClprTssFixtureHarvester.GENESIS_NETWORK_JSON);
                        ClprTssFixtureHarvester.installFixture(src, dst);
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
                        ClprTssFixtureHarvester.CACHE_DIR);
                // Cold path: ensure the WRAPS proving-key artifacts (extracted *.bin + wraps.sha384) are
                // in place before the node JVM starts, so the recursive prover can become ready. Use the
                // same path build.gradle.kts resolved and forwards to the node as
                // TSS_LIB_WRAPS_ARTIFACTS_PATH, so the artifacts version lives in one place and provisioning
                // always targets exactly what the node reads. No-op if already provisioned; runs once per
                // dir in this (single) parent JVM.
                final var wrapsArtifactsPath = System.getProperty("hapi.spec.tssLibWrapsArtifactsPath", "");
                if (!wrapsArtifactsPath.isBlank()) {
                    ClprWrapsProvingKeyInstaller.ensureProvisioned(Path.of(wrapsArtifactsPath));
                }
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
                            n.start(PER_NETWORK_CONFIG_VERSION);
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
            // Second pass: per-network TSS-readiness gate for tssPreload-opted networks, run in
            // parallel (one virtual thread per network) — every step below is per-network state
            // (own subprocess dir, own fixture filename, own reserved ports, ClprTssFixtureHarvester's
            // thread-safe shared mapper, distinct networks.set index), so the wall time is the slowest single
            // network's cold bootstrap, not the sum across networks.
            //
            // Warm path (cached fixture preloaded at JVM start): returns ~immediately once the
            // preload log fires + first signed block arrives. Cold path (no fixture): waits up to
            // 25 min for the runtime WRAPS-extensible event (~14 min on a 1-node subprocess),
            // polls for [CLPR-SYNC-POINT] (first WRAPS-carrying block, ~seconds later), settles
            // POST_SYNC_POINT_SETTLE so a few WRAPS-carrying blocks accumulate, triggers a
            // freezeOnly to fire ONLY_FREEZE_BLOCK export of a TSS-enriched output/network.json,
            // harvests it into the cache, then restarts the network with the just-cached fixture
            // preloaded so the test runs against a warm-loaded network (byte-identical to every
            // subsequent run).
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final List<Future<Void>> futures = new ArrayList<>();
                for (int i = 0; i < networks.size(); i++) {
                    final int idx = i;
                    final var cfg = configs[idx];
                    if (!cfg.tssPreload()) continue;
                    futures.add(executor.submit(() -> {
                        final var n = networks.get(idx);
                        final boolean coldBootstrap = awaitTssReady(n);
                        if (coldBootstrap) {
                            log.info(
                                    "[CLPR-FIXTURE] '{}' cold WRAPS bootstrap complete — awaiting sync-point log",
                                    n.name());
                            awaitWrapsSyncPoint(n);
                            Thread.sleep(POST_SYNC_POINT_SETTLE.toMillis());
                            // Trigger the single ONLY_FREEZE_BLOCK export, then harvest the fixture.
                            log.info("[CLPR-FIXTURE] '{}' triggering freeze to flush TSS-enriched snapshot", n.name());
                            runFreezeForExport(n);
                            ClprTssFixtureHarvester.harvestFreshFixtureOrThrow(n);
                            // Restart with the just-cached per-node fixtures preloaded so the test runs
                            // against a warm-loaded network rather than a frozen one.
                            if (!ClprTssFixtureHarvester.fixturePresent(resolveName(cfg))) {
                                throw new IllegalStateException(
                                        "Cold-bootstrap harvest reported success but per-node fixtures are missing for '"
                                                + resolveName(cfg) + "'");
                            }
                            final var freshNetwork = restartWithFixture(cfg, n);
                            // Distinct index per task; f.get() below establishes the happens-before edge.
                            networks.set(idx, freshNetwork);
                            // The fresh network is warm-preloaded — captureConfigProof will see
                            // WARM_PRELOADED and skip its own settle without needing the BOOTSTRAP_HANDLED
                            // flag, so no entry to add here.
                        }
                        return null;
                    }));
                }
                for (final var f : futures) {
                    try {
                        f.get();
                    } catch (final ExecutionException e) {
                        throw new RuntimeException("Network TSS bootstrap failed", e.getCause());
                    }
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
     * Seeds {@link #RESERVATIONS_BY_NAME} from the ports baked into the committed fixtures, so
     * {@link #resolveFirstGrpcPort} hands each fixtured network back its own capture-time base (a warm
     * start reproduces the fixture's in-state gossip/service ports verbatim, with no JSON patching),
     * while a network without a fixture is allocated a slot that avoids all of them.
     *
     * <p>Runs once per JVM, under the same class lock as {@code resolveFirstGrpcPort}. Each network is
     * read once by streaming only its {@code nodeMetadata} (never the ~42MB {@code tssMetadata}); the
     * base is the lowest node gRPC (service) port and the footprint is {@code nodeCount * PORTS_PER_NODE},
     * matching {@code resolveFirstGrpcPort}'s own reservation shape.
     */
    private static synchronized void ensureFixturePortReservations() {
        if (fixturePortReservationsSeeded) {
            return;
        }
        fixturePortReservationsSeeded = true;
        if (!Files.isDirectory(ClprTssFixtureHarvester.CACHE_DIR)) {
            return;
        }
        try (var files = Files.list(ClprTssFixtureHarvester.CACHE_DIR)) {
            files.sorted().forEach(path -> {
                final String network = ClprTssFixtureHarvester.fixtureNetworkName(
                        path.getFileName().toString());
                if (network == null || RESERVATIONS_BY_NAME.containsKey(network)) {
                    return;
                }
                try {
                    final int[] baseAndSize = ClprTssFixtureHarvester.fixtureBaseAndNodeCount(path);
                    if (baseAndSize == null) {
                        return;
                    }
                    final int base = baseAndSize[0];
                    final int footprint = baseAndSize[1] * PORTS_PER_NODE;
                    final Reservation conflict = firstOverlapping(base, base + footprint);
                    if (conflict != null) {
                        log.warn(
                                "[CLPR-FIXTURE] fixture '{}' base [{}, {}) overlaps '{}' [{}, {}); not reserving",
                                network,
                                base,
                                base + footprint,
                                conflict.name(),
                                conflict.base(),
                                conflict.end());
                        return;
                    }
                    RESERVATIONS_BY_NAME.put(network, new Reservation(network, base, footprint));
                    log.info(
                            "[CLPR-FIXTURE] reserved ports [{}, {}) for fixtured network '{}'",
                            base,
                            base + footprint,
                            network);
                } catch (final IOException e) {
                    log.warn("[CLPR-FIXTURE] could not read fixture {} for port reservation: {}", path, e.getMessage());
                }
            });
        } catch (final IOException e) {
            log.warn("[CLPR-FIXTURE] could not enumerate fixtures for port reservation: {}", e.getMessage());
        }
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
        MultiNetworkLifecycleTest.register(resolveName(cfg));
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
                final var src = resolveCachedFixturePath(resolveName(cfg));
                final var dst =
                        node.getExternalPath(DATA_CONFIG_DIR).resolve(ClprTssFixtureHarvester.GENESIS_NETWORK_JSON);
                ClprTssFixtureHarvester.installFixture(src, dst);
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
        fresh.start(PER_NETWORK_CONFIG_VERSION);
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
