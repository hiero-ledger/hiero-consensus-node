// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import static com.hedera.services.bdd.junit.hedera.embedded.AbstractEmbeddedHedera.NANOS_IN_A_SECOND;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.PENDING;
import static com.hedera.services.bdd.junit.hedera.subprocess.ConditionStatus.REACHED;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.awaitStatus;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;

import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.node.app.Hedera;
import com.hedera.services.bdd.junit.hedera.AbstractNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNetwork;
import com.hedera.services.bdd.junit.hedera.HederaNode;
import com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget;
import com.hedera.services.bdd.junit.hedera.subprocess.PrometheusClient;
import com.hedera.services.bdd.spec.TargetNetworkType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.TimeManager;

/**
 * A Hedera network based on Otter's container environment.
 */
public class OtterTurtleNetwork extends AbstractNetwork implements HederaNetwork {

    private static final Logger log = LogManager.getLogger();

    private static final PrometheusClient PROMETHEUS_CLIENT = new PrometheusClient();

    private final Network network;
    private final Map<Long, OtterTurtleNode> nodes;
    private final TimeManager timeManager;
    private final String configTxt;
    private final AtomicBoolean stillRunning = new AtomicBoolean(true);
    private final AtomicInteger nextNano = new AtomicInteger();

    /**
     * Constructs a new OtterContainerNetwork.
     *
     * @param networkName the name of the network
     * @param network the Otter network
     * @param nodes the nodes in the network
     * @param timeManager the time manager for the network
     * @param configTxt the config.txt for the network
     */
    public OtterTurtleNetwork(
            @NonNull final String networkName,
            @NonNull final Network network,
            @NonNull final List<OtterTurtleNode> nodes,
            final TimeManager timeManager,
            @NonNull final String configTxt) {
        super(networkName, nodes.stream().map(HederaNode.class::cast).toList());
        this.network = requireNonNull(network);
        this.nodes =
                nodes.stream().collect(Collectors.toMap(node -> node.getAccountId().accountNum(), Function.identity()));
        this.timeManager = requireNonNull(timeManager);
        this.configTxt = requireNonNull(configTxt);
        Executors.newSingleThreadScheduledExecutor(
                getStaticThreadManager().createThreadFactory("platform-core", "TickerThread"))
                        .submit(this::ticker);
    }

    private void ticker() {
        while (stillRunning.get()) {
            timeManager.waitFor(Duration.ofSeconds(1L));
            try {
                Thread.sleep(Duration.ofSeconds(1L));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Ticker thread exiting");
    }

    @NonNull
    @Override
    public Response send(
            @NonNull final Query query,
            @NonNull final HederaFunctionality functionality,
            @NonNull final AccountID nodeAccountId,
            final boolean asNodeOperator) {
        final var node = nodes.get(nodeAccountId.getAccountNum());
        return node.send(query, asNodeOperator);
    }

    @Override
    public TransactionResponse submit(
            @NonNull final Transaction transaction,
            @NonNull final HederaFunctionality functionality,
            @NonNull final SystemFunctionalityTarget target,
            @NonNull final AccountID nodeAccountId) {
        final var node = nodes.get(nodeAccountId.getAccountNum());
        return node.submit(transaction);
    }

    @Override
    public TargetNetworkType type() {
        return TargetNetworkType.OTTER_TURTLE_NETWORK;
    }

    @Override
    public void start() {
        network.start();
        for (final HederaNode node : nodes.values()) {
            node.initWorkingDir(configTxt);
            node.start();
        }
    }

    @Override
    public void terminate() {
        network.shutdown();
        for (final HederaNode node : nodes.values()) {
            node.stopFuture();
        }
        if (!nodes.values().isEmpty()) {
            final OtterTurtleNode node = nodes.values().iterator().next();
            try (final AutoCloseableWrapper<State> state = node.state("SETTLEMENT")) {
                final RunningHashes runningHashes = state.get()
                        .getReadableStates("BlockRecordService")
                        .<RunningHashes>getSingleton("RUNNING_HASHES")
                        .get();
                if (runningHashes != null) {
                    log.info("Final record running hash - {}", runningHashes.runningHash().toHex());
                }
            }
        }
        stillRunning.set(false);
    }

    @Override
    public void awaitReady(@NonNull final Duration timeout) {
        final Instant deadline = Instant.now().plus(timeout);
        for (final OtterTurtleNode node : nodes.values()) {
            awaitStatus(node, Duration.between(Instant.now(), deadline), ACTIVE);
            final Hedera hedera = node.hedera();
            assert hedera != null;
            if (!hedera.systemEntitiesCreated()) {
                conditionFuture(() -> hedera.systemEntitiesCreated() ? REACHED : PENDING, () -> 1)
                        .orTimeout(1, TimeUnit.SECONDS)
                        .join();
            }
        }
        if (Instant.now().isAfter(deadline)) {
            throw new RuntimeException("Network was not ready in time");
        }
    }

    @Override
    public PrometheusClient prometheusClient() {
        return PROMETHEUS_CLIENT;
    }

    @NonNull
    public Timestamp nextValidStart() {
        int candidateNano = nextNano.getAndIncrement();
        if (candidateNano >= NANOS_IN_A_SECOND) {
            candidateNano = 0;
            nextNano.set(1);
        }
        final var then = timeManager.now().minusSeconds(2L).minusNanos(candidateNano);
        return Timestamp.newBuilder()
                .setSeconds(then.getEpochSecond())
                .setNanos(then.getNano())
                .build();
    }

    public Instant now() {
        return timeManager.now();
    }
}
