// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.prCheckOverrides;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigRealm;
import static com.hedera.services.bdd.spec.HapiPropertySource.getConfigShard;
import static com.hedera.services.bdd.spec.HapiSpec.networkHapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.remembering;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest;
import com.hedera.services.bdd.junit.MultiNetworkHapiTest.Network;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final String CAPTURED_PROPS_KEY = "capturedProps";
    private static final String TEST_ID_KEY = "multiNetworkTestId";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
    /**
     * Config version every multi-network network starts at, regardless of upgrade restarts other networks have
     * already done (see {@link SubProcessNetwork#start(int)}).
     */
    private static final int GENESIS_CONFIG_VERSION = 0;
    /** How long to wait for an already-started shared network to be ACTIVE again before reusing it. */
    private static final Duration SHARED_REUSE_ACTIVE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Shared networks that have been started, by name. A network is started on the first test of its network
     * group (see {@link #getOrStartShared}), reused by the group's later tests, and removed when the group's
     * last test tears it down.
     */
    public static final Map<String, SubProcessNetwork> SHARED_NETWORKS = new ConcurrentHashMap<>();

    /**
     * Canonical {@code @Network} config per shared-network name, recorded up front by
     * {@code SharedMultiNetworkLauncherSessionListener} (which also reserves each one's ports). A network
     * whose name is a key here is SHARED: it is started lazily on first demand (see
     * {@link #getOrStartShared}) and kept warm in {@link #SHARED_NETWORKS} for reuse. A test whose network
     * name is absent fails; the extension never starts networks per test.
     */
    public static final Map<String, Network> DECLARED_CONFIGS = new ConcurrentHashMap<>();

    /**
     * Admits the network groups of shared networks under the node budget. Every enabled test on shared
     * networks is registered here by {@code SharedMultiNetworkLauncherSessionListener} during its test-plan walk.
     */
    public static final MultiNetworkGroupQueue NETWORK_GROUP_QUEUE =
            new MultiNetworkGroupQueue(MultiNetworkGroupQueue.DEFAULT_CAPACITY);

    /** Guards the one-time driver-log reconfigure done on the first lazily-started shared network. */
    private static final AtomicBoolean SHARED_LOGGING_CONFIGURED = new AtomicBoolean(false);

    /** System property keys consumed by {@code log4j2-test-client.xml}'s {@code RollingFile} appender. */
    private static final String TEST_CLIENT_LOG_FILE = "hapi.test.clients.log.file";

    private static final String TEST_CLIENT_LOG_FILE_PATTERN = "hapi.test.clients.log.filePattern";

    /** Sibling dir (of the per-network {@code <scope>-test/} dirs) for the multi-network driver log. */
    private static final String MULTINETWORK_LOG_DIR = "multinetwork-test-clients";

    static final String GRPC_TLS_PORT_KEY = "grpc.tlsPort";

    /**
     * Paths (relative to a node's working dir, which is its CWD) where {@link #provisionHapiTls} drops the
     * HAPI TLS certificate and key when {@code enableHapiTls = true} — the defaults of
     * {@code netty.tlsCrt.path} / {@code netty.tlsKey.path}, so no path override is needed.
     */
    private static final String HAPI_TLS_CRT_PATH = "hedera.crt";

    private static final String HAPI_TLS_KEY_PATH = "hedera.key";

    /**
     * Setup-override keys that are node-local and read ONLY at JVM startup (not dynamically reloadable):
     * the per-node HAPI TLS listener port. It is seeded per node into {@code application.properties} by
     * {@link #seedPerNodeApplicationOverrides}; applying it via the runtime 0.0.121 network override (see
     * {@link #applySetupOverrides}) is a no-op at runtime and would shadow a later per-node
     * {@code application.properties} change on restart (breaking tlsPort rotation).
     */
    private static final Set<String> STARTUP_ONLY_NODE_KEYS = Set.of(GRPC_TLS_PORT_KEY);

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
     * lookups for the same name return the cached base.
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

    @Override
    public void beforeEach(@NonNull final ExtensionContext ctx) {
        findAnnotation(ctx).ifPresent(annotation -> {
            store(ctx).put(ANNOTATION_KEY, annotation);
            final var configs = annotation.value();
            // Per-network serialization is done by JUnit via ClprNetworkLocksProvider (a @ResourceLock
            // provider on @MultiNetworkHapiTest): same-network tests take turns (READ overlaps, a @Leaky
            // WRITE runs alone), different groups run in parallel. So there is no lock to take here.
            final var names = Arrays.stream(configs)
                    .map(MultiNetworkExtension::resolveName)
                    .toList();
            final var undeclared = names.stream()
                    .filter(name -> !DECLARED_CONFIGS.containsKey(name))
                    .toList();
            if (!undeclared.isEmpty()) {
                throw new IllegalStateException("Networks " + undeclared + " for test " + ctx.getDisplayName()
                        + " were not declared by SharedMultiNetworkLauncherSessionListener (is the test @Disabled?)");
            }
            // Lazy start: get each shared network, starting it on first demand and
            // reusing it thereafter. Start under the canonical config the launcher reserved ports
            // for, not this test's copy, so every test on the name hits the same subprocess.
            // Wait for this test's network group to be admitted under the node budget, then lazily boot
            // its networks. The test id is stored first, so afterEach counts the test as finished even
            // if admission or the boot throws.
            final String testId = ctx.getUniqueId();
            store(ctx).put(TEST_ID_KEY, testId);
            final var group = NETWORK_GROUP_QUEUE.awaitTurn(testId);
            final var networks = new SubProcessNetwork[configs.length];
            // READ tests of one group can get here together. Boot (or reuse) under a per-group lock, so a
            // failed boot is torn down and marked failed before another test of the group can see the
            // networks missing and boot them again outside the node budget.
            final var bootLock = GROUP_BOOT_LOCKS.computeIfAbsent(group.id(), id -> new ReentrantLock());
            lockManaged(bootLock);
            try {
                // Throws if an earlier test of the group failed to boot; returns at once while it runs
                NETWORK_GROUP_QUEUE.awaitTurn(testId);
                try {
                    startSharedNetworks(configs, networks);
                } catch (final RuntimeException | Error e) {
                    // Tear down whatever started and free the group's nodes right away; its remaining
                    // tests then fail fast instead of retrying the boot
                    log.warn("[MultiNetworkExtension] Networks of network group [{}] failed to start", group.id(), e);
                    tearDownSharedGroup(group);
                    NETWORK_GROUP_QUEUE.bootFailed(testId);
                    throw e;
                }
            } finally {
                bootLock.unlock();
            }
            log.info("[MultiNetworkExtension] Using shared networks {} for test {}", names, ctx.getDisplayName());
            store(ctx).put(NETWORKS_KEY, networks);
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
        @SuppressWarnings("unchecked")
        final var captured =
                (Map<SubProcessNetwork, Map<String, String>>) store(ctx).remove(CAPTURED_PROPS_KEY, Map.class);
        // Restore each network by replaying the pre-test value of every key this test touched,
        // so shared networks are clean for the next test.
        if (networks != null && captured != null) {
            restoreCapturedProperties(networks, captured);
        }
        // A shared network group stays warm across all its tests; its last test tears it down and frees its nodes
        final String testId = store(ctx).remove(TEST_ID_KEY, String.class);
        if (testId != null) {
            finishSharedTests(testId);
        }
        if (networks != null) {
            wipeClprPeerEndpointsCache(networks);
        }
        store(ctx).remove(PARAM_INDEXES_KEY);
    }

    /**
     * Boots (or reuses) the shared networks {@code configs} name into {@code networks}, and waits for every node
     * to be ACTIVE.
     */
    private static void startSharedNetworks(
            @NonNull final Network[] configs, @NonNull final SubProcessNetwork[] networks) {
        // Boot the group's networks in PARALLEL (one virtual thread per network) rather than one
        // after another: getOrStartShared is start-once per name (its own per-name lock), and
        // distinct names boot independently, so a group's start is the slowest single network's
        // time, not the sum. Warm reuse (cache hit) returns ~immediately, so already-started
        // networks cost nothing here.
        try (final var startExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<?>> startFutures = new ArrayList<>();
            for (int i = 0; i < configs.length; i++) {
                final int idx = i;
                startFutures.add(startExecutor.submit(
                        () -> networks[idx] = getOrStartShared(DECLARED_CONFIGS.get(resolveName(configs[idx])))));
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
        // SubProcessNetwork#awaitReady memoizes its result for the lifetime of the instance and
        // is never re-armed, so it is a no-op on reuse. A shared network that an earlier suite
        // left non-ACTIVE (e.g. freeze/shutdown/restart) would
        // otherwise be handed straight to applySetupOverrides, whose specs then fail with
        // PLATFORM_NOT_ACTIVE. Re-verify per node; this returns immediately when already ACTIVE.
        for (final var network : networks) {
            network.nodes().forEach(node -> awaitStatus(node, SHARED_REUSE_ACTIVE_TIMEOUT, ACTIVE));
        }
    }

    /**
     * Marks the registered tests under {@code uniqueId} (a test, or a container whose tests never ran) as
     * finished, and tears down every shared network group whose last test this was.
     */
    public static void finishSharedTests(@NonNull final String uniqueId) {
        for (final var group : NETWORK_GROUP_QUEUE.testsFinished(uniqueId)) {
            try {
                tearDownSharedGroup(group);
            } finally {
                NETWORK_GROUP_QUEUE.release(group);
            }
        }
    }

    /** Terminates whichever of {@code group}'s shared networks are started and drops them from the warm cache. */
    private static void tearDownSharedGroup(@NonNull final NetworkGroup group) {
        final var networks = group.networkNames().stream()
                .map(SHARED_NETWORKS::remove)
                .filter(Objects::nonNull)
                .toArray(SubProcessNetwork[]::new);
        terminateNetworks(networks);
    }

    private static void terminateNetworks(@NonNull final SubProcessNetwork[] networks) {
        // Collect URIs before terminating so we can clean up stale channels.
        // Dead channels accumulate in the static HapiClients.channelPools and cause
        // "Connection refused" on subsequent runs because the round-robin picks them.
        final List<String> uris = new ArrayList<>();
        for (final var n : networks) {
            n.nodes().forEach(node -> uris.add(node.getHost() + ":" + node.getGrpcPort()));
        }
        for (final var n : networks) {
            safeTerminate(n);
        }
        HapiClients.removeChannelsFor(uris);
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
     * Reserves the gRPC port window for each declared network WITHOUT starting any nodes. Called
     * once up front by the launcher-session listener (with explicit-port networks sorted first) so that
     * shared networks starting lazily and out of order can't land on each other's ports.
     */
    public static void reservePorts(@NonNull final Network[] configs) {
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

    /** Per-network-group locks, so the tests of a group boot (or reuse) its networks one at a time. */
    private static final Map<String, ReentrantLock> GROUP_BOOT_LOCKS = new ConcurrentHashMap<>();

    /**
     * Acquires {@code lock} through {@link ForkJoinPool#managedBlock}, so a JUnit worker waiting for another test
     * of its group to finish booting is compensated for instead of starving the pool.
     */
    private static void lockManaged(@NonNull final ReentrantLock lock) {
        try {
            ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override
                public boolean block() throws InterruptedException {
                    lock.lockInterruptibly();
                    return true;
                }

                @Override
                public boolean isReleasable() {
                    return lock.isHeldByCurrentThread() || lock.tryLock();
                }
            });
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to boot a network group", e);
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

        final List<SubProcessNetwork> networks = new ArrayList<>();
        for (final var cfg : configs) {
            final long shard = cfg.shard() >= 0 ? cfg.shard() : getConfigShard();
            final long realm = cfg.realm() >= 0 ? cfg.realm() : getConfigRealm();
            final var network = SubProcessNetwork.newIsolatedNetwork(
                    resolveName(cfg), cfg.size(), nodeIdSlots(cfg), shard, realm, resolveFirstGrpcPort(cfg));

            // Collect setup overrides (defaults + annotation-declared) into one map so duplicates are
            // merged predictably. Defaults are seeded first so a per-test setupOverrides entry with the
            // same key wins.
            final var overrides = defaultSetupOverrides();
            for (final var o : cfg.setupOverrides()) {
                overrides.put(o.key(), o.value());
            }
            // Typed HAPI TLS port derived from @Network attributes, set last so it wins over anything a
            // test left in setupOverrides. firstHapiTlsPort is a base (seedPerNodeApplicationOverrides
            // offsets it per node).
            if (cfg.firstHapiTlsPort() > 0) {
                overrides.put(GRPC_TLS_PORT_KEY, Integer.toString(cfg.firstHapiTlsPort()));
            }
            seedPerNodeApplicationOverrides(network, overrides, nodeIdSlots(cfg));

            // Provision the per-network HAPI TLS certificate (cert/key PEMs into the working dir) before
            // the node JVM starts and looks for them to start its HAPI TLS listener.
            if (cfg.enableHapiTls()) {
                provisionHapiTls(cfg, network);
            }
            network.getPostInitWorkingDirActions().add(MultiNetworkExtension::alignGenesisNodeGossipEndpoints);

            networks.add(network);
        }
        try {
            // Spawn each network's subprocesses and wait for ACTIVE in parallel.
            // Each task is a single blocking awaitReady poll on its own log file.
            // Failures in any task surface via Future.get() and
            // are caught by the outer catch, which then terminates every started network.
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final List<Callable<Void>> tasks = networks.stream()
                        .<Callable<Void>>map(n -> () -> {
                            n.start(GENESIS_CONFIG_VERSION);
                            n.awaitReady(STARTUP_TIMEOUT);
                            return null;
                        })
                        .toList();
                awaitAllFailFast(executor, tasks, "Network startup failed");
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
     * Runs {@code tasks} on {@code executor} and waits for them all, but FAILS FAST: the moment any one
     * task throws, the still-running siblings are cancelled (interrupted) so the surrounding
     * try-with-resources {@code executor.close()} returns promptly instead of blocking on a sibling that
     * is mid-way through its (up to 25-minute) WRAPS/TSS wait. Uses an {@link ExecutorCompletionService}
     * so the first *completed* task is observed in completion order — a fast initialization failure is
     * detected immediately even if a slow task was submitted first. The original failure cause is
     * rewrapped in a {@link RuntimeException} with {@code failMessage} and rethrown to the caller, whose
     * outer catch terminates every started network.
     */
    private static void awaitAllFailFast(
            @NonNull final ExecutorService executor,
            @NonNull final List<Callable<Void>> tasks,
            @NonNull final String failMessage) {
        final var completion = new ExecutorCompletionService<Void>(executor);
        final List<Future<Void>> futures =
                tasks.stream().map(completion::submit).toList();
        try {
            for (int i = 0; i < futures.size(); i++) {
                // take() blocks for the next task to finish (in completion order), so a fast failure
                // is seen without first waiting on a slower sibling.
                completion.take().get();
            }
        } catch (final ExecutionException e) {
            futures.forEach(f -> f.cancel(true));
            throw new RuntimeException(failMessage, e.getCause());
        } catch (final InterruptedException e) {
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new RuntimeException(failMessage + " (interrupted while awaiting networks)", e);
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
        final int footprint = nodeIdSlots(cfg) * PORTS_PER_NODE;

        if (cfg.firstGrpcPort() > 0) {
            final int base = cfg.firstGrpcPort();
            final Reservation conflict = firstOverlapping(base, base + footprint);
            if (conflict != null) {
                throw new IllegalArgumentException(
                        "Explicit firstGrpcPort=" + base + " (node-id slots=" + nodeIdSlots(cfg)
                                + ", footprint " + footprint + ") on network '" + name + "' overlaps the ["
                                + conflict.base() + ", " + conflict.end() + ") range reserved by network '"
                                + conflict.name() + "'");
            }
            RESERVATIONS_BY_NAME.put(name, new Reservation(name, base, footprint));
            return base;
        }

        if (footprint > SHARED_PORT_SLOT) {
            throw new IllegalArgumentException("Network '" + name + "' node-id slots=" + nodeIdSlots(cfg)
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
     * Returns the setup overrides every multi-network test network starts with, before any test-declared
     * {@code setupOverrides}.
     */
    private static LinkedHashMap<String, String> defaultSetupOverrides() {
        final var overrides = new LinkedHashMap<String, String>();
        // Multi-network tests register connectors as part of setup; the prod default
        // clpr.minLockedStake (100M tinybars) requires a hbar transfer to register, which the
        // tests don't fund. Lower the threshold so simple test connectors succeed without
        // forcing every test annotation to repeat this override.
        overrides.put("clpr.minLockedStake", "100");
        overrides.put("clpr.nodeSubmitBundleMaxFee", "10000000000");
        overrides.put("clpr.verifierGasLimit", "5000000");
        // Task defaults apply at genesis and fixture restart, while per-test setup overrides win.
        overrides.putAll(prCheckOverrides());
        return overrides;
    }

    /**
     * Number of node ids {@code cfg}'s network lays out ports and per-node overrides for: the larger of its size and
     * {@link Network#maxNodeIds()}, so nodes added at runtime get collision-free ports and their overrides.
     */
    private static int nodeIdSlots(@NonNull final Network cfg) {
        return Math.max(cfg.size(), cfg.maxNodeIds());
    }

    /**
     * Seeds every node id's {@code application.properties} overrides from {@code overrides}, but assigns
     * the node-local {@code grpc.tlsPort} a <b>per-node</b> value ({@code base + nodeId}). Subprocess
     * nodes all share {@code 127.0.0.1}, so a single fixed {@code tlsPort} would collide when more
     * than one node tries to bind its HAPI TLS listener; {@code grpc.tlsPort} is a {@code @NodeProperty}
     * (node-local, not consensus state), so a distinct per-node value is legitimate. All other keys are
     * applied uniformly.
     */
    private static void seedPerNodeApplicationOverrides(
            @NonNull final SubProcessNetwork network,
            @NonNull final Map<String, String> overrides,
            final int nodeIdSlots) {
        if (overrides.isEmpty()) {
            return;
        }
        for (long id = 0; id < nodeIdSlots; id++) {
            final long nodeId = id;
            final List<String> flat = new ArrayList<>();
            overrides.forEach((k, v) -> {
                flat.add(k);
                if (GRPC_TLS_PORT_KEY.equals(k)) {
                    flat.add(Integer.toString(Integer.parseInt(v.trim()) + (int) nodeId));
                } else {
                    flat.add(v);
                }
            });
            network.getApplicationPropertyOverrides().put(id, List.copyOf(flat));
        }
        // Make the per-node tlsPort offset visible: the single base expands to base..base+nodeIdSlots-1.
        final var tlsBase = overrides.get(GRPC_TLS_PORT_KEY);
        if (tlsBase != null) {
            final int base = Integer.parseInt(tlsBase.trim());
            final var assignments = new StringBuilder();
            for (int id = 0; id < nodeIdSlots; id++) {
                assignments
                        .append(id == 0 ? "" : ", ")
                        .append("node")
                        .append(id)
                        .append('=')
                        .append(base + id);
            }
            log.info("[CLPR-TLS] '{}' per-node grpc.tlsPort: {}", network.name(), assignments);
        }
    }

    /**
     * Rewrites {@code node}'s {@code genesis-network.json} so each address-book {@code Node} lists its gossip
     * endpoints in the reverse of its roster entry's order, the address-book convention. A candidate roster built
     * from state ({@code ReadableNodeStore.snapshotOfFutureRoster}) swaps a node's first two gossip endpoints, so
     * only then does it match the roster the nodes started with. The generated genesis network lists both in the
     * same order, which only an {@code override-network.json} papers over; tests that adopt a changed roster from
     * state, the way a production upgrade does, need the two to agree.
     */
    private static void alignGenesisNodeGossipEndpoints(@NonNull final HederaNode node) {
        final Path genesisNetworkPath = node.getExternalPath(DATA_CONFIG_DIR).resolve(GENESIS_NETWORK_JSON);
        if (!Files.exists(genesisNetworkPath)) {
            return;
        }
        final var genesisNetwork = DiskStartupNetworks.loadNetworkFrom(genesisNetworkPath)
                .orElseThrow(() -> new IllegalStateException("Unreadable genesis network at " + genesisNetworkPath));
        final var aligned = genesisNetwork
                .copyBuilder()
                .nodeMetadata(genesisNetwork.nodeMetadata().stream()
                        .map(metadata -> {
                            final var endpoints = metadata.rosterEntryOrThrow().gossipEndpoint();
                            if (endpoints.size() < 2) {
                                return metadata;
                            }
                            return metadata.copyBuilder()
                                    .node(metadata.nodeOrThrow()
                                            .copyBuilder()
                                            .gossipEndpoint(endpoints.getLast(), endpoints.getFirst())
                                            .build())
                                    .build();
                        })
                        .toList())
                .build();
        try {
            Files.writeString(genesisNetworkPath, com.hedera.node.internal.network.Network.JSON.toJSON(aligned));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Generates a per-network self-signed HAPI TLS certificate and registers a post-init working-dir action that
     * drops its cert + PKCS#8 key PEMs into each node's working dir at {@code hedera.crt} /
     * {@code hedera.key} — the default {@code netty.tlsCrt.path} / {@code netty.tlsKey.path}, resolved against
     * the node's CWD (which is its working dir).
     */
    private static void provisionHapiTls(@NonNull final Network cfg, @NonNull final SubProcessNetwork network) {
        final HapiTlsCertificate certificate;
        try {
            certificate = new HapiTlsCertificate("hapi-tls-" + cfg.name());
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate HAPI TLS certificate for '" + cfg.name() + "'", e);
        }
        network.getPostInitWorkingDirActions().add(node -> {
            try {
                final var workingDir = node.getExternalPath(WORKING_DIR);
                certificate.writePem(workingDir.resolve(HAPI_TLS_CRT_PATH), workingDir.resolve(HAPI_TLS_KEY_PATH));
            } catch (final Exception e) {
                throw new UncheckedIOException(
                        new IOException("Failed to write HAPI TLS PEMs for '" + cfg.name() + "'", e));
            }
        });
    }

    /**
     * Deletes {@code data/clpr/peer-endpoints.json} on every node of every network. Safe on live
     * shared networks.
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
                // later per-node application.properties change across a restart (e.g. a tlsPort rotation).
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
