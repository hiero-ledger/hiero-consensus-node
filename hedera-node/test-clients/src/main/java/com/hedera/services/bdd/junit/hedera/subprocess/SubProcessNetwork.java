// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.subprocess;

import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static com.hedera.node.app.info.DiskStartupNetworks.OVERRIDE_NETWORK_JSON;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.classicMetadataFor;
import static com.hedera.services.bdd.junit.hedera.utils.AddressBookUtils.configTxtForLocal;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CANDIDATE_ROSTER_JSON;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.hedera.services.bdd.suites.utils.sysfiles.BookEntryPojo.asOctets;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.hiero.base.concurrent.interrupt.Uninterruptable.abortAndThrowIfInterrupted;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.tss.TssBlockHashSigner;
import com.hedera.node.app.workflows.handle.HandleWorkflow;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.AbstractGrpcNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OnlyRoster;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.spec.utilops.FakeNmt;
import com.hederahashgraph.api.proto.java.ServiceEndpoint;
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
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
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

    // 3 gRPC ports, 2 gossip ports, 1 Prometheus
    private static final int PORTS_PER_NODE = 6;
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final int FIRST_CANDIDATE_PORT = 30000;
    private static final int LAST_CANDIDATE_PORT = 40000;

    private static final String SUBPROCESS_HOST = "127.0.0.1";
    private static final ByteString SUBPROCESS_ENDPOINT = asOctets(SUBPROCESS_HOST);
    private static final GrpcPinger GRPC_PINGER = new GrpcPinger();
    private static final PrometheusClient PROMETHEUS_CLIENT = new PrometheusClient();

    private static int nextGrpcPort;
    private static int nextNodeOperatorPort;
    private static int nextInternalGossipPort;
    private static int nextExternalGossipPort;
    private static int nextPrometheusPort;
    private static boolean nextPortsInitialized = false;

    private final Map<Long, AccountID> pendingNodeAccounts = new HashMap<>();
    private final AtomicReference<DeferredRun> ready = new AtomicReference<>();

    private long maxNodeId;
    private String configTxt;
    private final String genesisConfigTxt;
    private final long shard;
    private final long realm;

    private final List<Consumer<HederaNode>> postInitWorkingDirActions = new ArrayList<>();
    private final List<Consumer<HederaNetwork>> onReadyListeners = new ArrayList<>();

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
        this.configTxt = configTxtForLocal(name(), nodes(), nextInternalGossipPort, nextExternalGossipPort);
        this.genesisConfigTxt = configTxt;
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
        final var sharedNetwork = liveNetwork(networkName, size, shard, realm);
        NetworkTargetingExtension.SHARED_NETWORK.set(sharedNetwork);
        return sharedNetwork;
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
     * Starts all nodes in the network.
     */
    @Override
    public void start() {
        nodes.forEach(node -> {
            node.initWorkingDir(configTxt);
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
                        .orTimeout(10, TimeUnit.SECONDS)
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
     * Returns the genesis <i>config.txt</i> file for the network.
     *
     * @return the genesis <i>config.txt</i> file
     */
    public String genesisConfigTxt() {
        return genesisConfigTxt;
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
        log.info("Reassigning ports for network '{}' starting from {}", name(), nextGrpcPort);
        reinitializePorts();
        log.info("  -> Network '{}' ports now starting from {}", name(), nextGrpcPort);
        nodes.forEach(node -> {
            final int nodeId = (int) node.getNodeId();
            ((SubProcessNode) node)
                    .reassignPorts(
                            nextGrpcPort + nodeId * 2,
                            nextNodeOperatorPort + nodeId,
                            nextInternalGossipPort + nodeId * 2,
                            nextExternalGossipPort + nodeId * 2,
                            nextPrometheusPort + nodeId);
        });
        final var weights = maybeLatestCandidateWeights();
        configTxt = configTxtForLocal(networkName, nodes, nextInternalGossipPort, nextExternalGossipPort, weights);
        refreshOverrideNetworks(ReassignPorts.YES);
    }

    /**
     * Refreshes the clients for the network, e.g. after reassigning metadata.
     */
    public void refreshClients() {
        HapiClients.tearDown();
        this.clients = HapiClients.clientsFor(this);
    }

    /**
     * Removes the matching node from the network and updates the <i>config.txt</i> file for the remaining nodes
     * from the given source.
     *
     * @param selector the selector for the node to remove
     */
    public void removeNode(@NonNull final NodeSelector selector) {
        requireNonNull(selector);
        final var node = getRequiredNode(selector);
        node.stopFuture();
        nodes.remove(node);
        configTxt = configTxtForLocal(
                networkName, nodes, nextInternalGossipPort, nextExternalGossipPort, latestCandidateWeights());
        refreshOverrideNetworks(ReassignPorts.NO);
    }

    /**
     * Adds a node with the given id to the network and updates the <i>config.txt</i> file for the remaining nodes
     * from the given source.
     *
     * @param nodeId the id of the node to add
     */
    public void addNode(final long nodeId) {
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
                        nextGrpcPort + (int) nodeId * 2,
                        nextNodeOperatorPort + (int) nodeId,
                        nextInternalGossipPort + (int) nodeId * 2,
                        nextExternalGossipPort + (int) nodeId * 2,
                        nextPrometheusPort + (int) nodeId,
                        shard,
                        realm),
                GRPC_PINGER,
                PROMETHEUS_CLIENT);
        final var accountId = pendingNodeAccounts.remove(nodeId);
        if (accountId != null) {
            node.reassignNodeAccountIdFrom(accountId);
        }
        nodes.add(insertionPoint, node);
        configTxt = configTxtForLocal(
                networkName, nodes, nextInternalGossipPort, nextExternalGossipPort, latestCandidateWeights());
        nodes.get(insertionPoint).initWorkingDir(configTxt);
        refreshOverrideNetworks(ReassignPorts.NO);
    }

    /**
     * Returns the gossip endpoints that can be automatically managed by this {@link SubProcessNetwork}
     * for the given node id.
     *
     * @return the gossip endpoints
     */
    public List<ServiceEndpoint> gossipEndpointsForNextNodeId() {
        final var nextNodeId = maxNodeId + 1;
        return List.of(
                endpointFor(nextInternalGossipPort + (int) nextNodeId * 2),
                endpointFor(nextExternalGossipPort + (int) nextNodeId * 2));
    }

    /**
     * Returns the gRPC endpoint that can be automatically managed by this {@link SubProcessNetwork}
     * for the given node id.
     *
     * @return the gRPC endpoint
     */
    public ServiceEndpoint grpcEndpointForNextNodeId() {
        final var nextNodeId = maxNodeId + 1;
        return endpointFor(nextGrpcPort + (int) nextNodeId * 2);
    }

    @Override
    protected HapiPropertySource networkOverrides() {
        return WorkingDirUtils.hapiTestStartupProperties();
    }

    /**
     * Creates a network of live (sub-process) nodes with the given name and size. This method is
     * synchronized because we don't want to re-use any ports across different networks.
     *
     * @param name the name of the network
     * @param size the number of nodes in the network
     * @return the network
     */
    private static synchronized HederaNetwork liveNetwork(
            @NonNull final String name, final int size, final long shard, final long realm) {
        if (!nextPortsInitialized) {
            initializeNextPortsForNetwork(size);
        }
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
                                        shard,
                                        realm),
                                GRPC_PINGER,
                                PROMETHEUS_CLIENT))
                        .toList(),
                shard,
                realm);
        Runtime.getRuntime().addShutdownHook(new Thread(network::terminate));
        return network;
    }

    /**
     * Writes the override <i>config.txt</i> and <i>override-network.json</i> files for each node in the network,
     * as implied by the current {@link SubProcessNetwork#configTxt} field. (Note the weights in this {@code configTxt}
     * field are maintained in very brittle fashion by getting up-to-date values from {@code node0}'s
     * <i>candidate-roster.json</i> file during the {@link FakeNmt} operations that precede the upgrade; at some point
     * we should clean this up.)
     */
    private void refreshOverrideNetworks(@NonNull final ReassignPorts reassignPorts) {
        log.info("Refreshing override networks for '{}' - \n{}", name(), configTxt);
        nodes.forEach(node -> {
            var overrideNetwork = WorkingDirUtils.networkFrom(configTxt, OnlyRoster.NO);
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

    private void reinitializePorts() {
        final var effectiveSize = (int) (maxNodeId + 1);
        final var firstGrpcPort = nodes().getFirst().getGrpcPort();
        final var totalPortsUsed = effectiveSize * PORTS_PER_NODE;
        final var newFirstGrpcPort = firstGrpcPort + totalPortsUsed;
        initializeNextPortsForNetwork(effectiveSize, newFirstGrpcPort);
    }

    private ServiceEndpoint endpointFor(final int port) {
        return ServiceEndpoint.newBuilder()
                .setIpAddressV4(SUBPROCESS_ENDPOINT)
                .setPort(port)
                .build();
    }

    private static void initializeNextPortsForNetwork(final int size) {
        initializeNextPortsForNetwork(size, randomPortAfter(FIRST_CANDIDATE_PORT, size * PORTS_PER_NODE));
    }

    /**
     * Initializes the next ports for the network with the given size and first gRPC port.
     *
     * @param size the number of nodes in the network
     * @param firstGrpcPort the first gRPC port
     */
    public static void initializeNextPortsForNetwork(final int size, final int firstGrpcPort) {
        // Suppose firstGrpcPort is 10000 with 4 nodes in the network, then the port assignments are,
        //   - grpcPort = 10000, 10002, 10004, 10006
        //   - nodeOperatorPort = 10008, 10009, 10010, 10011
        //   - gossipPort = 10012, 10014, 10016, 10018
        //   - gossipTlsPort = 10013, 10015, 10017, 10019
        //   - prometheusPort = 10020, 10021, 10022, 10023
        nextGrpcPort = firstGrpcPort;
        nextNodeOperatorPort = nextGrpcPort + 2 * size;
        nextInternalGossipPort = nextNodeOperatorPort + size;
        nextExternalGossipPort = nextInternalGossipPort + 1;
        nextPrometheusPort = nextInternalGossipPort + 2 * size;
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

    public void configureApplicationProperties(HederaNode node) {
        // Update bootstrap properties for the node from bootstrapPropertyOverrides if there are any
        final var nodeId = node.getNodeId();
        if (applicationPropertyOverrides.containsKey(nodeId)) {
            final var properties = applicationPropertyOverrides.get(nodeId);
            log.info("Configuring application properties for node {}: {}", nodeId, properties);
            Path appPropertiesPath = node.getExternalPath(APPLICATION_PROPERTIES);
            log.info(
                    "Attempting to update application.properties at path {} for node {}",
                    appPropertiesPath,
                    node.getNodeId());

            try {
                // First check if file exists and log current content
                if (Files.exists(appPropertiesPath)) {
                    String currentContent = Files.readString(appPropertiesPath);
                    log.info(
                            "Current application.properties content for node {}: {}", node.getNodeId(), currentContent);
                } else {
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

                // Verify the file was updated
                String updatedContent = Files.readString(appPropertiesPath);
                log.info(
                        "application.properties content after update for node {}: {}",
                        node.getNodeId(),
                        updatedContent);
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

    public Map<Long, List<String>> getApplicationPropertyOverrides() {
        return applicationPropertyOverrides;
    }
}
