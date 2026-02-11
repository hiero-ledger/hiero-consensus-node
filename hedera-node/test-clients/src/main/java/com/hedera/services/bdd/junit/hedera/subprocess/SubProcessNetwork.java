// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.node.app.info.DiskStartupNetworks.ARCHIVE;
import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static com.hedera.node.app.info.DiskStartupNetworks.OVERRIDE_NETWORK_JSON;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.LOG4J2_XML;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.classicMetadataFor;
import static com.hedera.services.bdd.junit.hedera.utils.NetworkUtils.generateNetworkConfig;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CANDIDATE_ROSTER_JSON;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo.asOctets;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.tss.TssBlockHashSigner;
import com.hedera.node.app.workflows.handle.HandleWorkflow;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.AbstractGrpcNetwork;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.junit.hedera.utils.NetworkUtils;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.props.MapPropertySource;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A network of Hedera nodes started in subprocesses and accessed via gRPC. Unlike
 * nodes in a remote or embedded network, its nodes support lifecycle operations like
 * stopping and restarting.
 */
public class SubProcessNetwork extends AbstractGrpcNetwork implements HederaNetwork {
    public static final String SHARED_NETWORK_NAME = "SHARED_NETWORK";
    private static final Logger log = LogManager.getLogger(SubProcessNetwork.class);

    // 3 gRPC ports, 2 gossip ports, 1 Prometheus, 1 reserved for JDWP/debug
    private static final int PORTS_PER_NODE = 7;
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final int FIRST_CANDIDATE_PORT = 30000;
    private static final int LAST_CANDIDATE_PORT = 40000;
    private static final int MAX_NODES_PER_NETWORK = 10;
    private static final String PRECONSENSUS_EVENTS_DIR = "preconsensus-events";
    private static final Pattern NUMBER_DIR_PATTERN = Pattern.compile("\\d+");
    private static final Duration SIGNED_STATE_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Pattern SOFTWARE_VERSION_TO_STRING_PATTERN = Pattern.compile(
            "SemanticVersion\\[major=(\\d+), minor=(\\d+), patch=(\\d+), pre=([^,]*), build=([^\\]]*)\\]");

    private static final String SUBPROCESS_HOST = "127.0.0.1";
    private static final ByteString SUBPROCESS_ENDPOINT = asOctets(SUBPROCESS_HOST);
    private static final GrpcPinger GRPC_PINGER = new GrpcPinger();
    private static final PrometheusClient PROMETHEUS_CLIENT = new PrometheusClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static int nextGrpcPort;
    private static int nextNodeOperatorPort;
    private static int nextInternalGossipPort;
    private static int nextExternalGossipPort;
    private static int nextPrometheusPort;
    private static int nextDebugPort;
    private static boolean nextPortsInitialized = false;
    private int baseGrpcPort;
    private int baseNodeOperatorPort;
    private int baseInternalGossipPort;
    private int baseExternalGossipPort;
    private int basePrometheusPort;
    private int baseDebugPort;
    private final Map<Long, AccountID> pendingNodeAccounts = new HashMap<>();
    private final AtomicReference<DeferredRun> ready = new AtomicReference<>();

    private long maxNodeId;
    private Network network;
    private final Network genesisNetwork;
    private final long shard;
    private final long realm;

    private final List<Consumer<HederaNode>> postInitWorkingDirActions = new ArrayList<>();
    private final List<Consumer<HederaNetwork>> onReadyListeners = new ArrayList<>();
    private BlockNodeMode blockNodeMode = BlockNodeMode.NONE;
    private final List<SimulatedBlockNodeServer> simulatedBlockNodes = new ArrayList<>();
    private Map<String, String> bootstrapOverrides;

    @Nullable
    private UnaryOperator<Network> overrideCustomizer = null;

    private final Map<Long, List<String>> applicationPropertyOverrides = new HashMap<>();

    /**
     * Wraps a runnable, allowing us to defer running it until we know we are the privileged runner
     * out of potentially several concurrent threads.
     */
    private static class DeferredRun {
        private static final Duration SCHEDULING_TIMEOUT = Duration.ofSeconds(10);

        /**
         * Counts down when the runnable has been scheduled by the creating thread.
         */
        private final CountDownLatch latch = new CountDownLatch(1);
        /**
         * The runnable to be completed asynchronously.
         */
        private final Runnable runnable;
        /**
         * The future result, if this supplier was the privileged one.
         */
        @Nullable
        private CompletableFuture<Void> future;

        public DeferredRun(@NonNull final Runnable runnable) {
            this.runnable = requireNonNull(runnable);
        }

        /**
         * Schedules the supplier to run asynchronously, marking it as the privileged supplier for this entity.
         */
        public void runAsync() {
            future = CompletableFuture.runAsync(runnable);
            latch.countDown();
        }

        /**
         * Blocks until the future result is available, then returns it.
         */
        public @NonNull CompletableFuture<Void> futureOrThrow() {
            awaitScheduling();
            return requireNonNull(future);
        }

        private void awaitScheduling() {
            if (future == null) {
                abortAndThrowIfInterrupted(
                        () -> {
                            if (!latch.await(SCHEDULING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                                throw new IllegalStateException(
                                        "Result future not scheduled within " + SCHEDULING_TIMEOUT);
                            }
                        },
                        "Interrupted while awaiting scheduling of the result future");
            }
        }
    }

    private SubProcessNetwork(
            @NonNull final String networkName, @NonNull final List<SubProcessNode> nodes, long shard, long realm) {
        super(networkName, nodes.stream().map(node -> (HederaNode) node).toList());
        this.shard = shard;
        this.realm = realm;
        this.maxNodeId =
                Collections.max(nodes.stream().map(SubProcessNode::getNodeId).toList());
        setBasePortsFromNextPorts();
        this.network = generateNetworkConfig(nodes(), nextInternalGossipPort, nextExternalGossipPort);
        this.genesisNetwork = network;
        this.postInitWorkingDirActions.add(this::configureApplicationProperties);
    }

    /**
     * Creates a shared network of sub-process nodes with the given size.
     *
     * @param size the number of nodes in the network
     * @return the shared network
     */
    public static synchronized HederaNetwork newSharedNetwork(
            String networkName, final int size, final long shard, final long realm) {
        if (NetworkTargetingExtension.SHARED_NETWORK.get() != null) {
            throw new UnsupportedOperationException("Only one shared network allowed per launcher session");
        }
        final var sharedNetwork = liveNetwork(networkName, size, shard, realm, -1);
        NetworkTargetingExtension.SHARED_NETWORK.set(sharedNetwork);
        return sharedNetwork;
    }

    /**
     * Creates an isolated subprocess network that is not registered as the shared network.
     *
     * @param networkName the name of the network
     * @param size the number of nodes
     * @param shard the shard id
     * @param realm the realm id
     * @return the isolated subprocess network
     */
    public static synchronized SubProcessNetwork newIsolatedNetwork(
            @NonNull final String networkName, final int size, final long shard, final long realm) {
        return newIsolatedNetwork(networkName, size, shard, realm, -1);
    }

    public static synchronized SubProcessNetwork newIsolatedNetwork(
            @NonNull final String networkName,
            final int size,
            final long shard,
            final long realm,
            final int firstGrpcPort) {
        requireNonNull(networkName);
        return liveNetwork(networkName, size, shard, realm, firstGrpcPort);
    }

    /**
     * Returns the network type; for now this is always
     * {@link TargetNetworkType#SUBPROCESS_NETWORK}.
     *
     * @return the network type
     */
    @Override
    public TargetNetworkType type() {
        return SUBPROCESS_NETWORK;
    }

    /**
     * Returns the initial network metadata used at startup.
     *
     * @return the genesis network metadata
     */
    public @NonNull Network genesisNetwork() {
        return genesisNetwork;
    }

    /**
     * Starts all nodes in the network.
     */
    @Override
    public void start() {
        nodes.forEach(node -> {
            node.initWorkingDir(network);
            executePostInitWorkingDirActions(node);
            node.start();
        });
    }

    /**
     * Add a listener to be notified when the network is ready.
     * @param listener the listener to notify when the network is ready
     */
    public void onReady(@NonNull final Consumer<HederaNetwork> listener) {
        requireNonNull(listener);
        if (ready.get() != null) {
            throw new IllegalStateException("Listeners must be registered before awaitReady()");
        }
        onReadyListeners.add(listener);
    }

    private void executePostInitWorkingDirActions(HederaNode node) {
        for (Consumer<HederaNode> action : postInitWorkingDirActions) {
            action.accept(node);
        }
    }

    /**
     * Forcibly stops all nodes in the network.
     */
    @Override
    public void terminate() {
        // Then stop network nodes first to prevent new streaming requests
        nodes.forEach(HederaNode::stopFuture);
    }

    /**
     * Waits for all nodes in the network to be ready within the given timeout.
     */
    @Override
    public void awaitReady(@NonNull final Duration timeout) {
        if (ready.get() == null) {
            log.info(
                    "Newly waiting for network '{}' to be ready in thread '{}'",
                    name(),
                    Thread.currentThread().getName());
            final var deferredRun = new DeferredRun(() -> {
                final var deadline = Instant.now().plus(timeout);
                // Block until all nodes are ACTIVE and ready to handle transactions
                nodes.forEach(node -> awaitStatus(node, Duration.between(Instant.now(), deadline), ACTIVE));
                nodes.forEach(node -> node.logFuture(HandleWorkflow.SYSTEM_ENTITIES_CREATED_MSG)
//                        .orTimeout(240, TimeUnit.SECONDS)
                        .join());
                nodes.forEach(node -> CompletableFuture.anyOf(
                                // Only the block stream uses TSS, so it is deactivated when streamMode=RECORDS
                                node.logFuture("blockStream.streamMode = RECORDS")
                                        .orTimeout(3, TimeUnit.MINUTES),
                                node.logFuture(TssBlockHashSigner.SIGNER_READY_MSG)
                                        .orTimeout(30, TimeUnit.MINUTES))
                        .join());
                this.clients = HapiClients.clientsFor(this);
            });
            // We only need one thread to wait for readiness
            if (ready.compareAndSet(null, deferredRun)) {
                deferredRun.runAsync();
                // Only attach onReady listeners once
                deferredRun.futureOrThrow().thenRun(() -> onReadyListeners.forEach(listener -> listener.accept(this)));
            }
        }
        ready.get().futureOrThrow().join();
    }

    /**
     * Updates the account id for the node with the given id.
     *
     * @param nodeId the node id
     * @param accountId the account id
     */
    public void updateNodeAccount(final long nodeId, final AccountID accountId) {
        final var nodes = nodesFor(byNodeId(nodeId));
        if (!nodes.isEmpty()) {
            ((SubProcessNode) nodes.getFirst()).reassignNodeAccountIdFrom(accountId);
        } else {
            pendingNodeAccounts.put(nodeId, accountId);
        }
    }

    /**
     * Sets a one-time use customizer for use during the next {@literal override-network.json} refresh.
     * @param overrideCustomizer the customizer to apply to the override network
     */
    public void setOneTimeOverrideCustomizer(@NonNull final UnaryOperator<Network> overrideCustomizer) {
        requireNonNull(overrideCustomizer);
        this.overrideCustomizer = overrideCustomizer;
    }

    /**
     * Refreshes the node <i>override-network.json</i> files with the weights from the latest
     * <i>candidate-roster.json</i> (if present); and reassigns ports to avoid binding conflicts.
     */
    public void refreshOverrideWithNewPorts() {
        log.info("Reassigning ports for network '{}' starting from {}", name(), baseGrpcPort);
        reinitializePorts();
        log.info("  -> Network '{}' ports now starting from {}", name(), baseGrpcPort);
        nodes.forEach(node -> {
            final int nodeId = (int) node.getNodeId();
            ((SubProcessNode) node)
                    .reassignPorts(
                            baseGrpcPort + nodeId * 2,
                            baseNodeOperatorPort + nodeId,
                            baseInternalGossipPort + nodeId * 2,
                            baseExternalGossipPort + nodeId * 2,
                            basePrometheusPort + nodeId,
                            baseDebugPort + nodeId);
        });
        final var weights = maybeLatestCandidateWeights();
        network = NetworkUtils.generateNetworkConfig(nodes, nextInternalGossipPort, nextExternalGossipPort, weights);
        refreshOverrideNetworks(ReassignPorts.YES);
    }

    /**
     * Refreshes the node <i>override-network.json</i> files using the current ports and latest weights.
     */
    public void refreshOverrideWithCurrentPorts() {
        refreshOverrideNetworks(ReassignPorts.NO);
    }

    /**
     * Refreshes the node <i>override-network.json</i> files using the current ports and latest weights.
     *
     * @param configVersion the configuration version (unused, kept for API compatibility)
     */
    public void refreshOverrideWithCurrentPortsForConfigVersion(final int configVersion) {
        refreshOverrideNetworks(ReassignPorts.NO);
    }

    /**
     * Refreshes the node <i>override-network.json</i> files after reassigning ports.
     *
     * @param configVersion the configuration version (unused, kept for API compatibility)
     * (FUTURE) Strip the unused param from all invocations.
     */
    public void refreshOverrideWithNewPortsForConfigVersion(final int configVersion) {
        refreshOverrideWithNewPorts();
    }

    /**
     * Removes any override network files regardless of config version.
     */
    public void clearOverrideNetworks() {
        nodes.forEach(node -> {
            final var dataConfigDir = node.getExternalPath(DATA_CONFIG_DIR);
            clearOverrideNetworkIn(dataConfigDir);
            clearOverrideNetworkIn(dataConfigDir.resolve(ARCHIVE));
        });
    }

    private void clearOverrideNetworkIn(@NonNull final Path rootDir) {
        deleteOverrideIfExists(rootDir.resolve(OVERRIDE_NETWORK_JSON));
        if (!Files.exists(rootDir)) {
            return;
        }
        try (var dirs = Files.list(rootDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> NUMBER_DIR_PATTERN
                            .matcher(dir.getFileName().toString())
                            .matches())
                    .forEach(dir -> deleteOverrideIfExists(dir.resolve(OVERRIDE_NETWORK_JSON)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Throws if any override-network.json files exist in the network config directories.
     */
    public void assertNoOverrideNetworks() {
        nodes.forEach(node -> {
            final var dataConfigDir = node.getExternalPath(DATA_CONFIG_DIR);
            final var baseOverride = dataConfigDir.resolve(OVERRIDE_NETWORK_JSON);
            if (Files.exists(baseOverride)) {
                throw new IllegalStateException(
                        "override-network.json present for node " + node.getNodeId() + " at " + baseOverride);
            }
            assertNoScopedOverride(dataConfigDir, node.getNodeId());
            final var archiveDir = dataConfigDir.resolve(ARCHIVE);
            final var archivedOverride = archiveDir.resolve(OVERRIDE_NETWORK_JSON);
            if (Files.exists(archivedOverride)) {
                throw new IllegalStateException(
                        "override-network.json present for node " + node.getNodeId() + " at " + archivedOverride);
            }
            assertNoScopedOverride(archiveDir, node.getNodeId());
        });
    }

    private static void assertNoScopedOverride(@NonNull final Path rootDir, final long nodeId) {
        if (!Files.exists(rootDir)) {
            return;
        }
        try (var dirs = Files.list(rootDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> NUMBER_DIR_PATTERN
                            .matcher(dir.getFileName().toString())
                            .matches())
                    .forEach(dir -> {
                        final var overridePath = dir.resolve(OVERRIDE_NETWORK_JSON);
                        if (Files.exists(overridePath)) {
                            throw new IllegalStateException(
                                    "override-network.json present for node " + nodeId + " at " + overridePath);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Refreshes the clients for the network, e.g. after reassigning metadata.
     */
    public void refreshClients() {
        this.clients = HapiClients.clientsFor(this);
        // Only rebuild channels that belong to this network to avoid disrupting other networks.
        HapiClients.rebuildChannelsForNodes(nodes());
    }

    /**
     * Removes the matching node from the network and updates the <i>config.txt</i> file for the remaining nodes
     * from the given source.
     *
     * @param selector the selector for the node to remove
     */
    public void removeNode(@NonNull final NodeSelector selector) {
        removeNodeInternal(selector, true);
    }

    /**
     * Removes the matching node from the network without refreshing override files.
     *
     * @param selector the selector for the node to remove
     */
    public void removeNodeWithoutOverrides(@NonNull final NodeSelector selector) {
        removeNodeInternal(selector, false);
    }

    /**
     * Adds a node with the given id to the network and updates the <i>config.txt</i> file for the remaining nodes
     * from the given source.
     *
     * @param nodeId the id of the node to add
     */
    public void addNode(final long nodeId) {
        addNodeInternal(nodeId, true);
    }

    /**
     * Adds a node with the given id to the network without refreshing override files.
     *
     * @param nodeId the id of the node to add
     */
    public void addNodeWithoutOverrides(final long nodeId) {
        addNodeInternal(nodeId, false);
    }

    private void removeNodeInternal(@NonNull final NodeSelector selector, final boolean refreshOverrides) {
        requireNonNull(selector);
        final var node = getRequiredNode(selector);
        node.stopFuture();
        nodes.remove(node);
        network = NetworkUtils.generateNetworkConfig(
                nodes, nextInternalGossipPort, nextExternalGossipPort, latestCandidateWeights());
        if (refreshOverrides) {
            refreshOverrideNetworks(ReassignPorts.NO);
        }
    }

    private void addNodeInternal(final long nodeId, final boolean refreshOverrides) {
        final var i = Collections.binarySearch(
                nodes.stream().map(HederaNode::getNodeId).toList(), nodeId);
        if (i >= 0) {
            throw new IllegalArgumentException("Node with id " + nodeId + " already exists in network");
        }
        this.maxNodeId = Math.max(maxNodeId, nodeId);
        final var insertionPoint = -i - 1;
        final var node = new SubProcessNode(
                classicMetadataFor(
                        (int) nodeId,
                        name(),
                        SUBPROCESS_HOST,
                        SHARED_NETWORK_NAME.equals(name()) ? null : name(),
                        baseGrpcPort,
                        baseNodeOperatorPort,
                        baseInternalGossipPort,
                        baseExternalGossipPort,
                        basePrometheusPort,
                        baseDebugPort,
                        shard,
                        realm),
                GRPC_PINGER,
                PROMETHEUS_CLIENT);
        final var accountId = pendingNodeAccounts.remove(nodeId);
        if (accountId != null) {
            node.reassignNodeAccountIdFrom(accountId);
        }
        nodes.add(insertionPoint, node);
        network = NetworkUtils.generateNetworkConfig(
                nodes, nextInternalGossipPort, nextExternalGossipPort, latestCandidateWeights());
        nodes.get(insertionPoint).initWorkingDir(network);
        ensureApplicationPropertyOverridesForNode(nodeId);
        executePostInitWorkingDirActions(node);
        if (refreshOverrides) {
            refreshOverrideNetworks(ReassignPorts.NO);
        }
    }

    private void ensureApplicationPropertyOverridesForNode(final long nodeId) {
        if (applicationPropertyOverrides.containsKey(nodeId)) {
            return;
        }
        final var node0Overrides = applicationPropertyOverrides.get(0L);
        if (node0Overrides != null) {
            applicationPropertyOverrides.put(nodeId, node0Overrides);
            return;
        }
        if (!applicationPropertyOverrides.isEmpty()) {
            applicationPropertyOverrides.put(
                    nodeId, applicationPropertyOverrides.values().iterator().next());
            return;
        }
        if (bootstrapOverrides == null || bootstrapOverrides.isEmpty()) {
            return;
        }
        final List<String> flattened = new ArrayList<>(bootstrapOverrides.size() * 2);
        bootstrapOverrides.forEach((key, value) -> {
            flattened.add(key);
            flattened.add(value);
        });
        applicationPropertyOverrides.put(nodeId, List.copyOf(flattened));
    }

    /**
     * Returns the latest signed state round for the given node, or {@code -1} if none exist.
     *
     * @param nodeId the node id to inspect
     * @return the latest signed state round, or {@code -1}
     */
    public long latestSignedStateRound(final long nodeId) {
        final var node = getRequiredNode(byNodeId(nodeId));
        final var signedStatesDir = node.getExternalPath(SAVED_STATES_DIR);
        return latestNumberedDirectory(signedStatesDir)
                .map(path -> Long.parseLong(path.getFileName().toString()))
                .orElse(-1L);
    }

    /**
     * Waits for the given node to have a signed state after the provided round.
     *
     * @param nodeId the node id to inspect
     * @param afterRound the round to wait past
     * @param timeout the maximum time to wait
     */
    public void awaitSignedStateAfterRound(final long nodeId, final long afterRound, @NonNull final Duration timeout) {
        requireNonNull(timeout);
        final var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (latestSignedStateRound(nodeId) > afterRound) {
                return;
            }
            sleep(SIGNED_STATE_POLL_INTERVAL);
        }
        throw new IllegalStateException(
                "No signed state after round " + afterRound + " for node " + nodeId + " within " + timeout);
    }

    /**
     * Waits for a signed state after the given round that includes the required roster entries.
     *
     * @param nodeId the node id to inspect
     * @param afterRound the round to wait past
     * @param timeout the maximum time to wait
     * @param requiredNodeIds roster entries that must be present
     */
    public void awaitSignedStateAfterRoundWithRosterEntries(
            final long nodeId,
            final long afterRound,
            @NonNull final Duration timeout,
            @NonNull final List<Long> requiredNodeIds) {
        requireNonNull(timeout);
        requireNonNull(requiredNodeIds);
        if (requiredNodeIds.isEmpty()) {
            throw new IllegalArgumentException("Required node ids must not be empty");
        }
        final var deadline = Instant.now().plus(timeout);
        var latestRound = afterRound;
        while (Instant.now().isBefore(deadline)) {
            final var currentRound = latestSignedStateRound(nodeId);
            if (currentRound > latestRound) {
                latestRound = currentRound;
                final var rosterPath = signedStateRosterPath(nodeId, currentRound);
                if (rosterPath.isPresent() && rosterIncludesNodeIds(rosterPath.get(), requiredNodeIds)) {
                    return;
                }
            }
            sleep(SIGNED_STATE_POLL_INTERVAL);
        }
        throw new IllegalStateException("No signed state after round " + afterRound + " for node " + nodeId + " within "
                + timeout + " that includes roster entries " + requiredNodeIds);
    }

    /**
     * Returns the configuration version encoded in the latest signed state metadata for the given node.
     *
     * @param nodeId the node id to inspect
     * @return the config version, or {@code 0} if it cannot be determined
     */
    public int currentConfigVersion(final long nodeId) {
        final var node = getRequiredNode(byNodeId(nodeId));
        final var signedStatesDir = node.getExternalPath(SAVED_STATES_DIR);
        final var latestStateDir = latestNumberedDirectory(signedStatesDir).orElse(null);
        if (latestStateDir == null) {
            return 0;
        }
        final var metadataPath = latestStateDir.resolve("stateMetadata.txt");
        if (!Files.exists(metadataPath)) {
            return 0;
        }
        try {
            final var metadata = SavedStateMetadata.parse(metadataPath);
            return configVersionFromSoftwareVersion(metadata.softwareVersion());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int configVersionFromSoftwareVersion(@NonNull final String softwareVersion) {
        if (softwareVersion.isBlank() || "null".equalsIgnoreCase(softwareVersion)) {
            return 0;
        }
        final var matcher = SOFTWARE_VERSION_TO_STRING_PATTERN.matcher(softwareVersion);
        final String build;
        if (matcher.matches()) {
            build = matcher.group(5);
        } else {
            try {
                build = HapiUtils.fromString(softwareVersion).build();
            } catch (Exception e) {
                return 0;
            }
        }
        return configVersionFromBuild(build);
    }

    private static int configVersionFromBuild(@NonNull final String build) {
        if (build.isBlank()) {
            return 0;
        }
        try {
            final var start = build.indexOf('c') >= 0 ? build.indexOf('c') + 1 : 0;
            return Integer.parseInt(build.substring(start));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Copies the latest signed state and preconsensus event files from the source node to the target node.
     *
     * @param sourceNodeId the node id to copy from
     * @param targetNodeId the node id to copy to
     */
    public void copyLatestSignedStateAndPces(final long sourceNodeId, final long targetNodeId) {
        if (sourceNodeId == targetNodeId) {
            throw new IllegalArgumentException("Source and target node ids must differ");
        }
        final var sourceNode = getRequiredNode(byNodeId(sourceNodeId));
        final var targetNode = getRequiredNode(byNodeId(targetNodeId));
        final var sourceSignedStatesDir = sourceNode.getExternalPath(SAVED_STATES_DIR);
        final var latestStateDir = latestNumberedDirectory(sourceSignedStatesDir)
                .orElseThrow(() -> new IllegalStateException("No signed states found for node " + sourceNodeId));
        final var targetSignedStatesDir = targetNode.getExternalPath(SAVED_STATES_DIR);
        WorkingDirUtils.rm(targetSignedStatesDir);
        WorkingDirUtils.copyDirectoryUnchecked(
                latestStateDir, targetSignedStatesDir.resolve(latestStateDir.getFileName()));
        final var sourcePcesDir = preconsensusEventsDir(sourceNode);
        if (!Files.exists(sourcePcesDir)) {
            throw new IllegalStateException("No preconsensus events found at " + sourcePcesDir);
        }
        final var targetPcesDir = preconsensusEventsDir(targetNode);
        WorkingDirUtils.rm(targetPcesDir);
        WorkingDirUtils.copyDirectoryUnchecked(sourcePcesDir, targetPcesDir);
        log.info(
                "Copied signed state '{}' and PCES from node {} to node {}",
                latestStateDir.getFileName(),
                sourceNodeId,
                targetNodeId);
    }

    public enum PortType {
        GRPC,
        NODE_OPERATOR,
        INTERNAL_GOSSIP,
        EXTERNAL_GOSSIP,
        PROMETHEUS,
        DEBUG
    }

    public int portForNodeId(@NonNull final PortType portType, final long nodeId) {
        requireNonNull(portType);
        final int offset = Math.toIntExact(nodeId);
        return switch (portType) {
            case GRPC -> baseGrpcPort + offset * 2;
            case NODE_OPERATOR -> baseNodeOperatorPort + offset;
            case INTERNAL_GOSSIP -> baseInternalGossipPort + offset * 2;
            case EXTERNAL_GOSSIP -> baseExternalGossipPort + offset * 2;
            case PROMETHEUS -> basePrometheusPort + offset;
            case DEBUG -> baseDebugPort + offset;
        };
    }

    /**
     * Returns the gossip endpoints that can be automatically managed by this {@link SubProcessNetwork}
     * for the given node id.
     *
     * @param nodeId the node id to allocate ports for
     * @return the gossip endpoints
     */
    public List<ServiceEndpoint> gossipEndpointsForNodeId(final long nodeId) {
        return List.of(
                endpointFor(portForNodeId(PortType.INTERNAL_GOSSIP, nodeId)),
                endpointFor(portForNodeId(PortType.EXTERNAL_GOSSIP, nodeId)));
    }

    /**
     * Returns the gossip endpoints that can be automatically managed by this {@link SubProcessNetwork}
     * for the given node id.
     *
     * @return the gossip endpoints
     */
    public List<ServiceEndpoint> gossipEndpointsForNextNodeId() {
        return gossipEndpointsForNodeId(maxNodeId + 1);
    }

    /**
     * Returns the gRPC endpoint that can be automatically managed by this {@link SubProcessNetwork}
     * for the given node id.
     *
     * @param nodeId the node id to allocate ports for
     * @return the gRPC endpoint
     */
    public ServiceEndpoint grpcEndpointForNodeId(final long nodeId) {
        return endpointFor(portForNodeId(PortType.GRPC, nodeId));
    }

    /**
     * Returns the gRPC endpoint that can be automatically managed by this {@link SubProcessNetwork}
     * for the given node id.
     *
     * @return the gRPC endpoint
     */
    public ServiceEndpoint grpcEndpointForNextNodeId() {
        return grpcEndpointForNodeId(maxNodeId + 1);
    }

    private static Optional<Path> latestNumberedDirectory(@NonNull final Path root) {
        if (!Files.exists(root)) {
            return Optional.empty();
        }
        long latest = -1;
        Path latestPath = null;
        try (final var stream = Files.newDirectoryStream(root)) {
            for (final var path : stream) {
                if (Files.isDirectory(path)
                        && NUMBER_DIR_PATTERN
                                .matcher(path.getFileName().toString())
                                .matches()) {
                    final var value = Long.parseLong(path.getFileName().toString());
                    if (value > latest) {
                        latest = value;
                        latestPath = path;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.ofNullable(latestPath);
    }

    private Optional<Path> signedStateRosterPath(final long nodeId, final long round) {
        final var node = getRequiredNode(byNodeId(nodeId));
        final var signedStatesDir = node.getExternalPath(SAVED_STATES_DIR);
        final var rosterPath = signedStatesDir.resolve(Long.toString(round)).resolve("currentRoster.json");
        return Files.exists(rosterPath) ? Optional.of(rosterPath) : Optional.empty();
    }

    /**
     * Returns whether the roster JSON includes all required node ids.
     *
     * @param rosterPath path to the roster JSON
     * @param requiredNodeIds required node ids
     * @return true if all required ids are present
     */
    private static boolean rosterIncludesNodeIds(
            @NonNull final Path rosterPath, @NonNull final List<Long> requiredNodeIds) {
        final Set<Long> discoveredIds = new HashSet<>();
        try (var input = Files.newInputStream(rosterPath)) {
            final var root = OBJECT_MAPPER.readTree(input);
            collectNodeIds(root, discoveredIds);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return requiredNodeIds.stream().allMatch(discoveredIds::contains);
    }

    /**
     * Collects all {@code nodeId} values from a JSON tree.
     *
     * <p>This avoids substring matching errors such as 1 matching 10.
     *
     * @param node the current JSON node
     * @param nodeIds the set to populate with parsed ids
     */
    private static void collectNodeIds(@NonNull final JsonNode node, @NonNull final Set<Long> nodeIds) {
        if (node.isObject()) {
            final var fields = node.fields();
            while (fields.hasNext()) {
                final var entry = fields.next();
                if ("nodeId".equals(entry.getKey())) {
                    final var value = entry.getValue();
                    if (value.isNumber()) {
                        nodeIds.add(value.longValue());
                    } else if (value.isTextual()) {
                        try {
                            nodeIds.add(Long.parseLong(value.asText()));
                        } catch (NumberFormatException ignore) {
                            // Ignore malformed nodeId fields.
                        }
                    }
                }
                collectNodeIds(entry.getValue(), nodeIds);
            }
        } else if (node.isArray()) {
            for (final var child : node) {
                collectNodeIds(child, nodeIds);
            }
        }
    }

    private static Path preconsensusEventsDir(@NonNull final HederaNode node) {
        return node.getExternalPath(WORKING_DIR)
                .resolve(WorkingDirUtils.DATA_DIR)
                .resolve(ProcessUtils.SAVED_STATES_DIR)
                .resolve(PRECONSENSUS_EVENTS_DIR)
                .resolve(Long.toString(node.getNodeId()));
    }

    private static void sleep(@NonNull final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for signed state", e);
        }
    }

    private Map<Long, com.hedera.hapi.node.base.ServiceEndpoint> currentGrpcServiceEndpoints() {
        return nodes().stream()
                .collect(toMap(
                        HederaNode::getNodeId,
                        node -> HapiPropertySource.asServiceEndpoint(node.getHost() + ":" + node.getGrpcPort())));
    }

    @Override
    protected HapiPropertySource networkOverrides() {
        final var baseProperties = WorkingDirUtils.hapiTestStartupProperties();
        if (bootstrapOverrides == null || bootstrapOverrides.isEmpty()) {
            return baseProperties;
        }
        return HapiPropertySource.inPriorityOrder(new MapPropertySource(bootstrapOverrides), baseProperties);
    }

    /**
     * Creates a network of live (sub-process) nodes with the given name and size. This method is
     * synchronized because we don't want to re-use any ports across different networks.
     *
     * @param name the name of the network
     * @param size the number of nodes in the network
     * @return the network
     */
    private static synchronized SubProcessNetwork liveNetwork(
            @NonNull final String name,
            final int size,
            final long shard,
            final long realm,
            final int firstGrpcPortOverride) {
        final int blockSize = Math.max(size, MAX_NODES_PER_NETWORK);
        if (!nextPortsInitialized || firstGrpcPortOverride > 0) {
            if (firstGrpcPortOverride > 0) {
                initializeNextPortsForNetwork(blockSize, firstGrpcPortOverride);
            } else {
                initializeNextPortsForNetwork(blockSize);
            }
        }
        final int currentFirstGrpcPort = nextGrpcPort;
        final var network = new SubProcessNetwork(
                name,
                IntStream.range(0, size)
                        .mapToObj(nodeId -> new SubProcessNode(
                                classicMetadataFor(
                                        nodeId,
                                        name,
                                        SUBPROCESS_HOST,
                                        SHARED_NETWORK_NAME.equals(name) ? null : name,
                                        nextGrpcPort,
                                        nextNodeOperatorPort,
                                        nextInternalGossipPort,
                                        nextExternalGossipPort,
                                        nextPrometheusPort,
                                        nextDebugPort,
                                        shard,
                                        realm),
                                GRPC_PINGER,
                                PROMETHEUS_CLIENT))
                        .toList(),
                shard,
                realm);
        // Advance the port window by a full reserved block so subsequent networks cannot overlap.
        final int nextFirstGrpcPort = currentFirstGrpcPort + blockSize * PORTS_PER_NODE;
        initializeNextPortsForNetwork(blockSize, nextFirstGrpcPort);
        Runtime.getRuntime().addShutdownHook(new Thread(network::terminate));
        return network;
    }

    /**
     * Writes the override <i>override-network.json</i> files for each node in the network, as implied by the current
     * {@link SubProcessNetwork#network} field. (Note the weights in this network are maintained in very brittle
     * fashion by getting up-to-date values from {@code node0}'s <i>candidate-roster.json</i> file during the
     * {@link FakeNmt} operations that precede the upgrade; at some point we should clean this up.)
     */
    private void refreshOverrideNetworks(@NonNull final ReassignPorts reassignPorts) {
        log.info("Refreshing override networks for '{}' - \n{}", name(), network);
        nodes.forEach(node -> {
            var overrideNetwork =
                    WorkingDirUtils.networkFrom(network, WorkingDirUtils.OnlyRoster.NO, currentGrpcServiceEndpoints());
            if (overrideCustomizer != null) {
                // Apply the override customizer to the network
                overrideNetwork = overrideCustomizer.apply(overrideNetwork);
            }
            final var genesisNetworkPath = node.getExternalPath(DATA_CONFIG_DIR).resolve(GENESIS_NETWORK_JSON);
            final var isGenesis = genesisNetworkPath.toFile().exists();
            // Only write override-network.json if a node is not starting from genesis; otherwise it will adopt
            // an override roster in a later round after its genesis reconnect and immediately ISS
            if (!isGenesis) {
                try {
                    Files.writeString(
                            node.getExternalPath(DATA_CONFIG_DIR).resolve(OVERRIDE_NETWORK_JSON),
                            Network.JSON.toJSON(overrideNetwork));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (reassignPorts == ReassignPorts.YES) {
                // If reassigning points, ensure any genesis-network.json for this node has the new ports
                final var genesisNetwork =
                        DiskStartupNetworks.loadNetworkFrom(genesisNetworkPath).orElseThrow();
                final var nodePorts = overrideNetwork.nodeMetadata().stream()
                        .map(NodeMetadata::rosterEntryOrThrow)
                        .collect(toMap(RosterEntry::nodeId, RosterEntry::gossipEndpoint));
                final var updatedNetwork = genesisNetwork
                        .copyBuilder()
                        .nodeMetadata(genesisNetwork.nodeMetadata().stream()
                                .map(metadata -> withReassignedPorts(
                                        metadata,
                                        nodePorts.get(
                                                metadata.rosterEntryOrThrow().nodeId())))
                                .toList())
                        .build();
                try {
                    Files.writeString(genesisNetworkPath, Network.JSON.toJSON(updatedNetwork));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        overrideCustomizer = null;
    }

    private NodeMetadata withReassignedPorts(
            @NonNull final NodeMetadata metadata,
            @NonNull final List<com.hedera.hapi.node.base.ServiceEndpoint> endpoints) {
        return new NodeMetadata(
                metadata.rosterEntryOrThrow()
                        .copyBuilder()
                        .gossipEndpoint(endpoints)
                        .build(),
                metadata.nodeOrThrow()
                        .copyBuilder()
                        .gossipEndpoint(endpoints.getLast(), endpoints.getFirst())
                        .build());
    }

    private void deleteOverrideIfExists(@NonNull final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete override network file {}: {}", path, e.toString());
        }
    }

    private void reinitializePorts() {
        final var effectiveSize = (int) (maxNodeId + 1);
        final var reservedSize = Math.max(effectiveSize, MAX_NODES_PER_NETWORK);
        final var firstGrpcPort = nodes().getFirst().getGrpcPort();
        final var totalPortsUsed = reservedSize * PORTS_PER_NODE;
        final var newFirstGrpcPort = firstGrpcPort + totalPortsUsed;
        initializeNextPortsForNetwork(reservedSize, newFirstGrpcPort);
        setBasePortsFromNextPorts();
    }

    private void setBasePortsFromNextPorts() {
        baseGrpcPort = nextGrpcPort;
        baseNodeOperatorPort = nextNodeOperatorPort;
        baseInternalGossipPort = nextInternalGossipPort;
        baseExternalGossipPort = nextExternalGossipPort;
        basePrometheusPort = nextPrometheusPort;
        baseDebugPort = nextDebugPort;
    }

    private ServiceEndpoint endpointFor(final int port) {
        return ServiceEndpoint.newBuilder()
                .setIpAddressV4(SUBPROCESS_ENDPOINT)
                .setPort(port)
                .build();
    }

    private static synchronized void initializeNextPortsForNetwork(final int size) {
        final int reservedSize = Math.max(size, MAX_NODES_PER_NETWORK);
        final int configuredPort = Integer.getInteger("hapi.spec.initial.port", -1);
        final int firstGrpcPort = configuredPort > 0
                ? configuredPort
                : randomPortAfter(FIRST_CANDIDATE_PORT, reservedSize * PORTS_PER_NODE);
        initializeNextPortsForNetwork(reservedSize, firstGrpcPort);
    }

    /**
     * Initializes the next ports for the network with the given size and first gRPC port.
     *
     * Layout for reserved size=N:
     *   - grpcPort:            firstGrpcPort + 2*i
     *   - nodeOperatorPort:    firstGrpcPort + 2*N + i
     *   - gossipPort:          firstGrpcPort + 3*N + 2*i
     *   - gossipTlsPort:       firstGrpcPort + 3*N + 2*i + 1
     *   - prometheusPort:      firstGrpcPort + 5*N + i
     *   - debug (JDWP) port:   firstGrpcPort + 6*N + i
     *
     * @param size the number of nodes in the network
     * @param firstGrpcPort the first gRPC port
     */
    public static synchronized void initializeNextPortsForNetwork(final int size, final int firstGrpcPort) {
        final int reservedSize = Math.max(size, MAX_NODES_PER_NETWORK);
        nextGrpcPort = firstGrpcPort;
        nextNodeOperatorPort = nextGrpcPort + 2 * reservedSize;
        nextInternalGossipPort = nextNodeOperatorPort + reservedSize;
        nextExternalGossipPort = nextInternalGossipPort + 1;
        nextPrometheusPort = nextInternalGossipPort + 2 * reservedSize;
        nextDebugPort = nextPrometheusPort + reservedSize;
        nextPortsInitialized = true;
    }

    private static int randomPortAfter(final int firstAvailable, final int numRequired) {
        return RANDOM.nextInt(firstAvailable, LAST_CANDIDATE_PORT + 1 - numRequired);
    }

    /**
     * Loads and returns the node weights for the latest candidate roster, if available.
     *
     * @return the node weights, or an empty map if there is no <i>candidate-roster.json</i>
     */
    private Map<Long, Long> maybeLatestCandidateWeights() {
        try {
            return latestCandidateWeights();
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

    /**
     * Loads and returns the node weights for the latest candidate roster.
     *
     * @return the node weights
     * @throws IllegalStateException if the <i>candidate-roster.json</i> file cannot be read or parsed
     */
    private Map<Long, Long> latestCandidateWeights() {
        final var candidateRosterPath =
                nodes().getFirst().metadata().workingDirOrThrow().resolve(CANDIDATE_ROSTER_JSON);
        try (final var fin = Files.newInputStream(candidateRosterPath)) {
            final var network = Network.JSON.parse(new ReadableStreamingData(fin));
            return network.nodeMetadata().stream()
                    .map(NodeMetadata::rosterEntryOrThrow)
                    .collect(toMap(RosterEntry::nodeId, RosterEntry::weight));
        } catch (IOException | ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Finds an available TCP port by probing random candidates in the {@link #FIRST_CANDIDATE_PORT} to
     * {@link #LAST_CANDIDATE_PORT} range.
     *
     * @return an available port
     * @throws RuntimeException if no port can be found after a bounded number of attempts
     */
    public static int findAvailablePort() {
        // Find a random available port between 30000 and 40000
        int attempts = 0;
        while (attempts < 100) {
            int port = RANDOM.nextInt(FIRST_CANDIDATE_PORT, LAST_CANDIDATE_PORT);
            try (ServerSocket socket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                attempts++;
            }
        }
        throw new RuntimeException("Could not find available port after 100 attempts");
    }

    /**
     * Applies any configured application property overrides for the given node by appending key/value pairs to the
     * node's {@code application.properties} file.
     *
     * <p>This is used by various test extensions (e.g. multi-network and restart flows) to force specific settings
     * for a given node prior to process start.</p>
     *
     * @param node the node whose {@code application.properties} should be updated
     */
    public void configureApplicationProperties(HederaNode node) {
        // Update bootstrap properties for the node from bootstrapPropertyOverrides if there are any
        final var nodeId = node.getNodeId();
        if (applicationPropertyOverrides.containsKey(nodeId)) {
            final var properties = applicationPropertyOverrides.get(nodeId);
            // Don't log override values; these can occasionally include sensitive material (e.g. keys/certs).
            final var keys = new ArrayList<String>();
            for (int i = 0; i < properties.size(); i += 2) {
                keys.add(properties.get(i));
            }
            log.info("Configuring application properties for node {} keys: {}", nodeId, keys);
            Path appPropertiesPath = node.getExternalPath(APPLICATION_PROPERTIES);
            log.info("Attempting to update application.properties at path {} for node {}", appPropertiesPath, nodeId);

            try {
                if (!Files.exists(appPropertiesPath)) {
                    log.info(
                            "application.properties does not exist yet for node {}, will create new file",
                            node.getNodeId());
                }

                // Prepare the block stream config string
                StringBuilder propertyBuilder = new StringBuilder();
                for (int i = 0; i < properties.size(); i += 2) {
                    propertyBuilder.append(properties.get(i)).append("=").append(properties.get(i + 1));
                    if (i < properties.size() - 1) {
                        propertyBuilder.append(System.lineSeparator());
                    }
                }

                // Write the properties with CREATE and APPEND options
                Files.writeString(
                        appPropertiesPath,
                        propertyBuilder.toString(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("Failed to update application.properties for node {}: {}", node.getNodeId(), e.getMessage());
                throw new RuntimeException("Failed to update application.properties for node " + node.getNodeId(), e);
            }
        } else {
            log.info("No bootstrap property overrides for node {}", nodeId);
        }
    }

    public List<Consumer<HederaNode>> getPostInitWorkingDirActions() {
        return postInitWorkingDirActions;
    }

    @Override
    public long shard() {
        return shard;
    }

    @Override
    public long realm() {
        return realm;
    }

    @Override
    public PrometheusClient prometheusClient() {
        return PROMETHEUS_CLIENT;
    }

    /**
     * Configures the log level for the block node communication package in the node's log4j2.xml file.
     * This allows for more detailed logging of block streaming operations during tests.
     *
     * @param node the node whose logging configuration should be updated
     * @param logLevel the log level to set (e.g., "DEBUG", "INFO", "WARN")
     */
    public void configureBlockNodeCommunicationLogLevel(
            @NonNull final HederaNode node, @NonNull final String logLevel) {
        requireNonNull(node, "Node cannot be null");
        requireNonNull(logLevel, "Log level cannot be null");
        final Path loggerConfigPath = node.getExternalPath(LOG4J2_XML);
        try {
            // Read the existing XML file
            String xmlContent = Files.readString(loggerConfigPath);

            // Check if the logger configuration for streaming package exists
            if (xmlContent.contains("<Logger name=\"com.hedera.node.app.blocks.impl.streaming\" level=")) {
                // Update the existing logger configuration
                final String updatedXmlContent = xmlContent.replaceAll(
                        "<Logger name=\"com.hedera.node.app.blocks.impl.streaming\" level=\"[^\"]*\"",
                        "<Logger name=\"com.hedera.node.app.blocks.impl.streaming\" level=\"" + logLevel + "\"");

                // Write the updated XML back to the file
                Files.writeString(loggerConfigPath, updatedXmlContent);

                log.info("Updated existing com.hedera.node.app.blocks.impl.streaming logger to level {}", logLevel);
            } else {
                // If the logger configuration doesn't exist, add it
                final int insertPosition = xmlContent.lastIndexOf("</Loggers>");
                if (insertPosition != -1) {
                    // Create the new logger configuration
                    final StringBuilder newLogger = new StringBuilder();
                    newLogger
                            .append("    <Logger name=\"com.hedera.node.app.blocks.impl.streaming\" ")
                            .append("level=\"" + logLevel + "\" additivity=\"false\">\n")
                            .append("      <AppenderRef ref=\"Console\"/>\n")
                            .append("      <AppenderRef ref=\"RollingFile\"/>\n")
                            .append("    </Logger>\n\n");

                    // Insert the new logger configuration
                    final String updatedXmlContent =
                            xmlContent.substring(0, insertPosition) + newLogger + xmlContent.substring(insertPosition);

                    // Write the updated XML back to the file
                    Files.writeString(loggerConfigPath, updatedXmlContent);

                    log.info(
                            "Successfully added com.hedera.node.app.blocks.impl.streaming logger at level {}",
                            logLevel);
                } else {
                    log.info("Could not find </Loggers> tag in log4j2.xml");
                }
            }
        } catch (IOException e) {
            log.error("Error updating log4j2.xml: {}", e.getMessage());
        }
    }

    /**
     * Gets the current block node mode for this network.
     *
     * @return the current block node mode
     */
    public @NonNull BlockNodeMode getBlockNodeMode() {
        return blockNodeMode;
    }

    /**
     * Configure the block node mode for this network.
     * @param mode the block node mode to use
     */
    public void setBlockNodeMode(@NonNull final BlockNodeMode mode) {
        requireNonNull(mode, "Block node mode cannot be null");
        log.info("Setting block node mode from {} to {}", this.blockNodeMode, mode);
        this.blockNodeMode = mode;
    }

    public Map<Long, List<String>> getApplicationPropertyOverrides() {
        return applicationPropertyOverrides;
    }

    public void addBootstrapOverrides(@NonNull final Map<String, String> overrides) {
        requireNonNull(overrides);
        if (bootstrapOverrides == null) {
            bootstrapOverrides = new LinkedHashMap<>();
        }
        bootstrapOverrides.putAll(overrides);
    }
}
