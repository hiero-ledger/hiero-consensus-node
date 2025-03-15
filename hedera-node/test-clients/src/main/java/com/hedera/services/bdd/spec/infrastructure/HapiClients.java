// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.pbjToProto;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.props.NodeConnectInfo;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.service.proto.java.AddressBookServiceGrpc.AddressBookServiceBlockingStub;
import com.hederahashgraph.service.proto.java.ConsensusServiceGrpc.ConsensusServiceBlockingStub;
import com.hederahashgraph.service.proto.java.CryptoServiceGrpc.CryptoServiceBlockingStub;
import com.hederahashgraph.service.proto.java.FileServiceGrpc.FileServiceBlockingStub;
import com.hederahashgraph.service.proto.java.FreezeServiceGrpc.FreezeServiceBlockingStub;
import com.hederahashgraph.service.proto.java.NetworkServiceGrpc.NetworkServiceBlockingStub;
import com.hederahashgraph.service.proto.java.ScheduleServiceGrpc.ScheduleServiceBlockingStub;
import com.hederahashgraph.service.proto.java.SmartContractServiceGrpc.SmartContractServiceBlockingStub;
import com.hederahashgraph.service.proto.java.TokenServiceGrpc.TokenServiceBlockingStub;
import com.hederahashgraph.service.proto.java.UtilServiceGrpc;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HapiClients {
    static final Logger log = LogManager.getLogger(HapiClients.class);
    // The deadline for the server to respond to a blocking unary call
    private static final long DEADLINE_SECS = 30L;

    private final List<HederaNode> nodes;
    private final List<NodeConnectInfo> nodeInfos;
    /**
     * Id of node-{host, port} pairs to use for non-workflow operations
     */
    private final Map<AccountID, String> stubIds;
    /**
     * Id of node-{host, port} pairs to use for non-workflow operations using TLS ports
     */
    private final Map<AccountID, String> tlsStubIds;
    /**
     * Id of node-{host, port} pairs to use for node operator operations
     */
    private final Map<AccountID, String> nodeOperatorStubIds;

    /**
     * Maps from a node connection URI to a pool of {@link ChannelStubs} that have been created for that URI.
     * Many specs can run in parallel against the same nodes, so we use a concurrent map and copy-on-write
     * lists to avoid concurrent modification exceptions.
     *
     * <p>When a {@link HapiSpec} asks for stubs to a particular node's services, we return one of
     * the channels in the pool via round-robin selection.
     */
    private static final Map<String, List<ChannelStubs>> channelPools = new ConcurrentSkipListMap<>();
    /**
     * Maps from a channel stub ID (a URI string) to a sequence counter.
     * This map is used for selecting channels from the channel pool for that stub ID, supporting round-robin
     * channel stub selection, which minimizes the probability of a channel being used simultaneously.
     * This helps to work around issues in the GRPC client code.
     */
    private static final Map<String, AtomicInteger> stubSequences = new ConcurrentSkipListMap<>();

    private static final Function<String, List<ChannelStubs>> COPY_ON_WRITE_LIST_SUPPLIER =
            ignore -> new CopyOnWriteArrayList<>();

    /**
     * Given a specific node connection URI, we continue creating new {@link ChannelStubs} as new {@link HapiSpec}'s
     * initialize until we have reached thd number.
     */
    private static final int MAX_DESIRED_CHANNELS_PER_NODE = 50;

    private static final long MINIMUM_REBUILD_INTERVAL_MS = 30_000L;
    private static final AtomicReference<Instant> LAST_CHANNEL_REBUILD_TIME = new AtomicReference<>();

    private static ManagedChannel createNettyChannel(
            boolean useTls, final String host, final int port, final int tlsPort) {
        try {
            ManagedChannel channel;

            if (useTls) {
                String[] protocols = new String[] {"TLSv1.2", "TLSv1.3"};
                List<String> ciphers = Arrays.asList(
                        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                        "TLS_AES_256_GCM_SHA384");
                SslContextBuilder contextBuilder = GrpcSslContexts.configure(SslContextBuilder.forClient());
                contextBuilder.protocols(protocols).ciphers(ciphers, SupportedCipherSuiteFilter.INSTANCE);

                channel = NettyChannelBuilder.forAddress(host, tlsPort)
                        .negotiationType(NegotiationType.TLS)
                        .sslContext(contextBuilder.build())
                        .overrideAuthority("127.0.0.1")
                        .build();
            } else {
                channel = NettyChannelBuilder.forAddress(host, port)
                        .usePlaintext()
                        .build();
            }
            return channel;
        } catch (Exception e) {
            log.error("Error creating Netty channel", e);
        }
        return null;
    }

    private void ensureChannelStubsInPool(@NonNull final NodeConnectInfo node, final boolean useTls) {
        requireNonNull(node);
        final var channelUri = useTls ? node.tlsUri() : node.uri();
        final var existingPool = channelPools.computeIfAbsent(channelUri, COPY_ON_WRITE_LIST_SUPPLIER);
        if (existingPool.size() < MAX_DESIRED_CHANNELS_PER_NODE) {
            final var channel = createNettyChannel(useTls, node.getHost(), node.getPort(), node.getTlsPort());
            requireNonNull(channel, "Cannot continue without Netty channel");
            existingPool.add(ChannelStubs.from(channel, node, useTls));
        }
        stubSequences.putIfAbsent(channelUri, new AtomicInteger());
    }

    private void ensureNodeOperatorChannelStubsInPool(@NonNull final HederaNode node) {
        requireNonNull(node);
        final var channelUri = uri(node.getHost(), node.getGrpcNodeOperatorPort());
        final var existingPool = channelPools.computeIfAbsent(channelUri, COPY_ON_WRITE_LIST_SUPPLIER);
        if (existingPool.size() < MAX_DESIRED_CHANNELS_PER_NODE) {
            final var channel = createNettyChannel(false, node.getHost(), node.getGrpcNodeOperatorPort(), -1);
            requireNonNull(channel, "FATAL: Cannot continue without additional Netty channel");
            existingPool.add(ChannelStubs.from(
                    channel,
                    new NodeConnectInfo(node.hapiSpecInfo(
                            node.getAccountId().shardNum(), node.getAccountId().realmNum())),
                    false));
        }
        stubSequences.putIfAbsent(channelUri, new AtomicInteger());
    }

    private HapiClients(final Supplier<List<NodeConnectInfo>> nodeInfosSupplier) {
        this.nodes = Collections.emptyList();
        this.nodeInfos = nodeInfosSupplier.get();
        stubIds = nodeInfos.stream().collect(Collectors.toMap(NodeConnectInfo::getAccount, NodeConnectInfo::uri));
        tlsStubIds = nodeInfos.stream().collect(Collectors.toMap(NodeConnectInfo::getAccount, NodeConnectInfo::tlsUri));
        nodeOperatorStubIds = Collections.emptyMap();
        nodeInfos.forEach(node -> {
            ensureChannelStubsInPool(node, false);
            ensureChannelStubsInPool(node, true);
        });
    }

    private HapiClients(final List<HederaNode> nodes) {
        this.nodes = nodes;
        this.nodeInfos = Collections.emptyList();
        stubIds = nodes.stream()
                .collect(Collectors.toMap(
                        n -> pbjToProto(n.getAccountId(), com.hedera.hapi.node.base.AccountID.class, AccountID.class),
                        n -> uri(n.getHost(), n.getGrpcPort())));
        tlsStubIds = Collections.emptyMap();
        nodeOperatorStubIds = nodes.stream()
                .collect(Collectors.toMap(
                        n -> pbjToProto(n.getAccountId(), com.hedera.hapi.node.base.AccountID.class, AccountID.class),
                        n -> uri(n.getHost(), n.getGrpcNodeOperatorPort())));
        nodes.forEach(node -> {
            ensureChannelStubsInPool(new NodeConnectInfo(node.hapiSpecInfo()), false);
            ensureChannelStubsInPool(new NodeConnectInfo(node.hapiSpecInfo()), true);
            ensureNodeOperatorChannelStubsInPool(node);
        });
    }

    private String uri(String host, int port) {
        return String.format("%s:%d", host, port);
    }

    public static HapiClients clientsFor(HapiSpecSetup setup) {
        return new HapiClients(setup::nodes);
    }

    public static HapiClients clientsFor(@NonNull final HederaNetwork network) {
        requireNonNull(network);
        return new HapiClients(network.nodes());
    }

    /**
     * Retrieves a blocking stub for the FileService based on the provided parameters.
     *
     * This method obtains a stub from the pool, identified by the given AccountID,
     * TLS usage, and node operator status. It sets a deadline for the stub's
     * operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the FileService with a specified deadline
     */
    public FileServiceBlockingStub getFileSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .fileSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the TokenService based on the provided parameters.
     *
     * This method obtains a stub from the pool, identified by the given AccountID,
     * TLS usage, and node operator status. It sets a deadline for the stub's
     * operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the TokenService with a specified deadline
     */
    public TokenServiceBlockingStub getTokenSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .tokenSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the AddressBookService based on the provided parameters.
     *
     * This method returns a stub for making synchronous AddressBookService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the AddressBookService with a specified deadline
     */
    public AddressBookServiceBlockingStub getAddressBookSvcStub(
            AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .addressBookSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the CryptoService based on the provided parameters.
     *
     * This method returns a stub for making synchronous CryptoService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the CryptoService with a specified deadline
     */
    public CryptoServiceBlockingStub getCryptoSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .cryptoSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the FreezeService based on the provided parameters.
     *
     * This method returns a stub for making synchronous FreezeService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the FreezeService with a specified deadline
     */
    public FreezeServiceBlockingStub getFreezeSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .freezeSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the SmartContractService based on the provided parameters.
     *
     * This method returns a stub for making synchronous SmartContractService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the SmartContractService with a specified deadline
     */
    public SmartContractServiceBlockingStub getScSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .scSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the ConsensusService based on the provided parameters.
     *
     * This method returns a stub for making synchronous ConsensusService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the ConsensusService with a specified deadline
     */
    public ConsensusServiceBlockingStub getConsSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .consSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the NetworkService based on the provided parameters.
     *
     * This method returns a stub for making synchronous NetworkService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the NetworkService with a specified deadline
     */
    public NetworkServiceBlockingStub getNetworkSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .networkSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the ScheduleService based on the provided parameters.
     *
     * This method returns a stub for making synchronous ScheduleService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the ScheduleService with a specified deadline
     */
    public ScheduleServiceBlockingStub getScheduleSvcStub(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .scheduleSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves a blocking stub for the UtilService based on the provided parameters.
     *
     * This method returns a stub for making synchronous UtilService RPC calls,
     * identified by the given AccountID, with options for using TLS and acting as a node operator.
     * It sets a deadline for the stub's operations to ensure they complete within a specified time frame.
     *
     * @param nodeId the node for which the stub is requested
     * @param useTls whether to use TLS for secure communication
     * @param asNodeOperator whether to obtain the stub as a node operator
     * @return a blocking stub for the UtilService with a specified deadline
     */
    public UtilServiceGrpc.UtilServiceBlockingStub getUtilSvcStub(
            AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        return nextStubsFromPool(stubId(nodeId, useTls, asNodeOperator))
                .utilSvcStubs()
                .withDeadlineAfter(DEADLINE_SECS, TimeUnit.SECONDS);
    }

    private String stubId(AccountID nodeId, boolean useTls, boolean asNodeOperator) {
        if (useTls) {
            return tlsStubIds.get(nodeId);
        } else if (asNodeOperator) {
            return nodeOperatorStubIds.get(nodeId);
        } else {
            return stubIds.get(nodeId);
        }
    }

    private static synchronized ChannelStubs nextStubsFromPool(@NonNull final String stubId) {
        requireNonNull(stubId);
        final List<ChannelStubs> stubs = channelPools.get(stubId);
        if (stubs == null || stubs.isEmpty()) {
            throw new IllegalArgumentException("Should have ensured at least one channel in pool");
        }
        final AtomicInteger currentSequence = stubSequences.get(stubId);
        if (currentSequence == null) {
            throw new IllegalArgumentException("Should have assigned a sequence counter to the pool");
        }
        return stubs.get(currentSequence.getAndIncrement() % stubs.size());
    }

    @Override
    public String toString() {
        MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this);
        for (int i = 0; i < nodes.size(); i++) {
            helper.add(String.format("node%d", i), nodes.get(i).toString());
        }
        return helper.toString();
    }

    public static synchronized void rebuildChannels() {
        final var maybeLastRebuildTime = LAST_CHANNEL_REBUILD_TIME.get();
        if (maybeLastRebuildTime != null) {
            final var msSinceLastRebuild = System.currentTimeMillis() - maybeLastRebuildTime.toEpochMilli();
            if (msSinceLastRebuild < MINIMUM_REBUILD_INTERVAL_MS) {
                return;
            }
        }
        log.info("Shutting down all managed channels");
        shutdownChannels();
        final var allUris = new ArrayList<>(channelPools.keySet());
        allUris.forEach(uri -> {
            final var closedChannels = channelPools.get(uri);
            final var reopenedChannels = rebuiltChannelsLike(closedChannels);
            log.info("Reopened {} channels for {}", reopenedChannels.size(), uri);
            channelPools.put(uri, reopenedChannels);
        });
        LAST_CHANNEL_REBUILD_TIME.set(Instant.now());
        log.info("Finished rebuilding channels at {}", LAST_CHANNEL_REBUILD_TIME.get());
    }

    private static List<ChannelStubs> rebuiltChannelsLike(@NonNull final List<ChannelStubs> channelStubs) {
        final List<ChannelStubs> newChannelStubs = new CopyOnWriteArrayList<>();
        channelStubs.forEach(channelStub -> {
            final var node = channelStub.nodeConnectInfo();
            final var useTls = channelStub.useTls();
            final var channel =
                    requireNonNull(createNettyChannel(useTls, node.getHost(), node.getPort(), node.getTlsPort()));
            newChannelStubs.add(ChannelStubs.from(channel, node, useTls));
        });
        return newChannelStubs;
    }

    /** Close all netty channels that are opened for clients */
    private static void closeChannels() {
        if (channelPools.isEmpty()) {
            return;
        }
        shutdownChannels();
        channelPools.clear();
    }

    private static void shutdownChannels() {
        channelPools.forEach((uri, channelPool) -> channelPool.forEach(ChannelStubs::shutdown));
    }

    /**
     * Should only be called once after all tests are done.
     */
    public static void tearDown() {
        closeChannels();
    }
}
