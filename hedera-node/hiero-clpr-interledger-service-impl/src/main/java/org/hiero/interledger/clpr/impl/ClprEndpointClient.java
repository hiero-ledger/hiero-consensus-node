// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.interledger.clpr.ClprStateProofUtils.buildLocalClprStateProofWrapper;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageKey;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageQueueMetadata;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.bundleFirstMsgId;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.bundleLastMsgId;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.createBundle;
import static org.hiero.interledger.clpr.impl.ClprServiceImpl.RUNNING_HASH_SIZE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.client.ClprClient;
import org.hiero.interledger.clpr.impl.client.ClprConnectionManager;

/**
 * A CLPR Endpoint is responsible for connecting to remote CLPR Endpoints and exchanging state proofs with them.
 */
@Singleton
public class ClprEndpointClient {
    private static final Logger log = LogManager.getLogger(ClprEndpointClient.class);
    private static final int MAX_ENDPOINT_CYCLES = 2;
    private static final long BUNDLE_SIZE = 5;

    private boolean started = false;
    private final @NonNull ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            getStaticThreadManager().createThreadFactory("clpr", "EndpointManager"));
    private Future<?> routineFuture;

    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;
    private final ClprConnectionManager connectionManager;
    private final ClprStateProofManager stateProofManager;
    private final ConcurrentHashMap<org.hiero.hapi.interledger.state.clpr.ClprLedgerId, AtomicInteger>
            endpointCursorByLedgerId = new ConcurrentHashMap<>();
    private final Clock clock;

    private record RemoteEndpoint(
            ServiceEndpoint endpoint, @Nullable AccountID nodeAccountId) {}

    @Inject
    public ClprEndpointClient(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ExecutorService executor,
            @NonNull final Metrics metrics,
            @NonNull final ClprConnectionManager clprConnectionManager,
            @NonNull final ClprStateProofManager stateProofManager) {
        this(networkInfo, configProvider, clprConnectionManager, stateProofManager, Clock.systemUTC());
        requireNonNull(executor);
        requireNonNull(metrics);
    }

    ClprEndpointClient(
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprConnectionManager clprConnectionManager,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final Clock clock) {
        this.networkInfo = requireNonNull(networkInfo);
        this.configProvider = requireNonNull(configProvider);
        this.connectionManager = requireNonNull(clprConnectionManager);
        this.stateProofManager = requireNonNull(stateProofManager);
        this.clock = requireNonNull(clock);
    }

    /**
     * Performs a single maintenance cycle: publish the local configuration to known remotes and
     * pull newer remote configurations back into this network.
     */
    void runOnce() {
        try {
            final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
            if (!clprConfig.clprEnabled()) {
                log.debug("CLPR endpoint maintenance skipped; feature disabled");
                return;
            }

            final var localLedgerId = stateProofManager.getLocalLedgerId();
            if (localLedgerId == null || localLedgerId.ledgerId() == Bytes.EMPTY) {
                log.debug("CLPR endpoint maintenance awaiting local ledgerId bootstrap");
                return;
            }
            final var configsByLedgerId = stateProofManager.readAllLedgerConfigurations();
            final var localConfig = configsByLedgerId.get(localLedgerId);
            if (localConfig == null) {
                log.debug("CLPR endpoint maintenance found no local configuration for ledger {}", localLedgerId);
                return;
            }
            final var localProof = stateProofManager.getLedgerConfiguration(localLedgerId);
            if (localProof == null) {
                log.debug("CLPR endpoint maintenance skipped; no state proof available for ledger {}", localLedgerId);
                return;
            }
            final var selfNodeInfo = networkInfo.selfNodeInfo();
            final var localEndpoint = localServiceEndpoint();
            for (final var entry : configsByLedgerId.entrySet()) {
                final var remoteLedgerId = entry.getKey();
                if (localLedgerId.equals(remoteLedgerId)) {
                    continue;
                }
                final var storedRemoteConfig = entry.getValue();
                exchangeWithRemote(
                        selfNodeInfo, localEndpoint, localConfig, localProof, remoteLedgerId, storedRemoteConfig);
            }
        } catch (final Exception e) {
            log.error("CLPR endpoint maintenance failed; will retry on next cycle", e);
        }
    }

    private void scheduleRoutineActivity() {
        final var interval = configProvider
                .getConfiguration()
                .getConfigData(ClprConfig.class)
                .connectionFrequency();
        routineFuture = scheduler.scheduleAtFixedRate(this::runOnce, interval, interval, TimeUnit.MILLISECONDS);
    }

    public synchronized void start() {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.clprEnabled()) {
            log.info("CLPR is disabled, ClprEndpoint Client not started.");
            return;
        }
        if (!started) {
            log.info("Starting CLPR Endpoint...");
            scheduleRoutineActivity();
            started = true;
        }
    }

    public synchronized void stop() {
        if (started) {
            log.info("Stopping CLPR Endpoint...");
            if (routineFuture != null) {
                routineFuture.cancel(true);
            }
            started = false;
            routineFuture = null;
        }
    }

    private static String formatTimestamp(@Nullable final Timestamp timestamp) {
        if (timestamp == null) {
            return "<none>";
        }
        final long seconds = timestamp.seconds();
        final int nanos = timestamp.nanos();
        try {
            final Instant instant = Instant.ofEpochSecond(seconds, nanos);
            return "%d.%09d (%s)".formatted(seconds, nanos, instant);
        } catch (final DateTimeException e) {
            return "%d.%09d".formatted(seconds, nanos);
        }
    }

    private ServiceEndpoint localServiceEndpoint() {
        final var grpcConfig = configProvider.getConfiguration().getConfigData(GrpcConfig.class);
        // Transactions are only accepted on the primary gRPC port; the node-operator port supports queries only.
        final byte[] loopback = new byte[] {127, 0, 0, 1};
        final int port = grpcConfig.port();
        return ServiceEndpoint.newBuilder()
                .ipAddressV4(Bytes.wrap(loopback))
                .port(port)
                .build();
    }

    /**
     * Executes a single request/response pattern with a remote endpoint: publish local config, then pull remote config.
     */
    private void exchangeWithRemote(
            final com.hedera.node.app.spi.info.NodeInfo selfNodeInfo,
            final ServiceEndpoint localEndpoint,
            final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration localConfig,
            final com.hedera.hapi.block.stream.StateProof localProof,
            final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration storedRemoteConfig) {
        final var endpoints = candidateEndpoints(storedRemoteConfig);
        if (endpoints.length == 0) {
            log.debug(
                    "CLPR Endpoint: No reachable endpoint in remote config {}; skipping publish/pull", remoteLedgerId);
            return;
        }
        final var cursor = endpointCursorByLedgerId.computeIfAbsent(remoteLedgerId, id -> new AtomicInteger(0));
        final int size = endpoints.length;
        final int startIndex = Math.floorMod(cursor.get(), size);
        final int maxAttempts = size * MAX_ENDPOINT_CYCLES;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            final int index = Math.floorMod(startIndex + attempt, size);
            final var remoteEndpoint = endpoints[index];
            log.debug(
                    "CLPR Endpoint: Attempting publish/pull to remote ledger {} via endpoint {} (attempt {}/{})",
                    remoteLedgerId,
                    remoteEndpoint.endpoint(),
                    attempt + 1,
                    maxAttempts);
            if (exchangeWithRemoteEndpoint(
                    localConfig.ledgerId(),
                    remoteEndpoint,
                    selfNodeInfo,
                    localEndpoint,
                    localProof,
                    remoteLedgerId,
                    storedRemoteConfig)) {
                cursor.set(Math.floorMod(index + 1, size));
                return;
            }
        }
        cursor.set(Math.floorMod(startIndex + maxAttempts, size));
    }

    private boolean exchangeWithRemoteEndpoint(
            final ClprLedgerId localLedgerId,
            final RemoteEndpoint remoteEndpoint,
            final com.hedera.node.app.spi.info.NodeInfo selfNodeInfo,
            final ServiceEndpoint localEndpoint,
            final com.hedera.hapi.block.stream.StateProof localProof,
            final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration storedRemoteConfig) {
        try (final ClprClient remoteClient = connectionManager.createClient(remoteEndpoint.endpoint())) {
            final var selfAccount = selfNodeInfo.accountId();
            final var nodeAccountId =
                    remoteEndpoint.nodeAccountId() != null ? remoteEndpoint.nodeAccountId() : selfAccount;
            final var protocolOutcome = runProtocolWithRemote(
                    remoteClient,
                    localLedgerId,
                    selfAccount,
                    nodeAccountId,
                    localEndpoint,
                    localProof,
                    remoteLedgerId,
                    storedRemoteConfig,
                    remoteEndpoint.endpoint());
            if (!protocolOutcome) {
                return false;
            }
            log.info(
                    "CLPR Endpoint: Completed publish/pull for remote ledger {} via endpoint {}",
                    remoteLedgerId,
                    remoteEndpoint.endpoint());
            return true;
        } catch (final UnknownHostException e) {
            log.warn(
                    "CLPR Endpoint: Unable to resolve endpoint {} for remote ledger {}",
                    remoteEndpoint.endpoint(),
                    remoteLedgerId,
                    e);
        } catch (final Exception e) {
            log.warn(
                    "CLPR Endpoint: Publish/pull failed for remote ledger {} via endpoint {}",
                    remoteLedgerId,
                    remoteEndpoint.endpoint(),
                    e);
        }
        return false;
    }

    private boolean runProtocolWithRemote(
            @NonNull final ClprClient remoteClient,
            @NonNull final ClprLedgerId localLedgerId,
            @NonNull final AccountID selfAccount,
            @NonNull final AccountID nodeAccountId,
            @NonNull final ServiceEndpoint localEndpoint,
            @NonNull final com.hedera.hapi.block.stream.StateProof localProof,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration storedRemoteConfig,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        // exchange ledger configs
        final var ledgerSucceeded = pushPullLedgerConfiguration(
                remoteClient,
                selfAccount,
                nodeAccountId,
                localEndpoint,
                localProof,
                remoteLedgerId,
                storedRemoteConfig,
                remoteServiceEndpoint);
        if (!ledgerSucceeded) {
            return false;
        }

        final var localClient = createLocalClient(localEndpoint);

        // exchange messages
        final var pushPullQueueMetadata = pushPullQueueMessages(
                localLedgerId, remoteLedgerId, localClient, remoteClient, selfAccount, nodeAccountId);

        if (!pushPullQueueMetadata) {
            return false;
        }

        return true;
    }

    private boolean pushPullLedgerConfiguration(
            @NonNull final ClprClient remoteClient,
            @NonNull final AccountID selfAccount,
            @NonNull final AccountID nodeAccountId,
            @NonNull final ServiceEndpoint localEndpoint,
            @NonNull final com.hedera.hapi.block.stream.StateProof localProof,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration storedRemoteConfig,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        final var publishStatus = publishLocalConfigProofToRemote(remoteClient, selfAccount, nodeAccountId, localProof);
        final boolean publishSucceeded = isSuccessful(publishStatus);
        if (!publishSucceeded) {
            log.warn(
                    "CLPR Endpoint: Publish failed for remote ledger {} via endpoint {} (status {})",
                    remoteLedgerId,
                    remoteServiceEndpoint,
                    publishStatus);
        }

        final var fetchedProof = pullRemoteConfigProof(remoteClient, remoteLedgerId);
        boolean pullSucceeded = false;
        if (fetchedProof != null) {
            final var fetchedRemoteConfig = ClprStateProofUtils.extractConfiguration(fetchedProof);
            if (!remoteLedgerId.equals(fetchedRemoteConfig.ledgerId())) {
                log.debug(
                        "CLPR Endpoint: Skipping fetched config for unexpected ledger {}; expected {}",
                        fetchedRemoteConfig.ledgerId(),
                        remoteLedgerId);
            } else if (isNewerConfig(storedRemoteConfig, fetchedRemoteConfig)) {
                try (final ClprClient localClient = connectionManager.createClient(localEndpoint)) {
                    localClient.setConfiguration(selfAccount, selfAccount, fetchedProof);
                    pullSucceeded = true;
                } catch (final UnknownHostException e) {
                    log.warn(
                            "CLPR Endpoint: Unable to resolve local endpoint {} while storing config for remote ledger {}",
                            localEndpoint,
                            remoteLedgerId,
                            e);
                    return false;
                }
            } else {
                pullSucceeded = true;
            }
        }
        return publishSucceeded || pullSucceeded;
    }

    private @Nullable boolean pushPullQueueMessages(
            @NonNull final ClprLedgerId localLedgerId,
            @NonNull final ClprLedgerId remoteLedgerId,
            @NonNull final ClprClient localClient,
            @NonNull final ClprClient remoteClient,
            @NonNull final AccountID selfAccount,
            @NonNull final AccountID nodeAccount) {
        final var localLedgerIdString = localLedgerId.ledgerId().toString();
        final var ledgerLogPrefix = "CLPR Endpoint: "
                + localLedgerIdString
                        .substring(localLedgerIdString.length() - 4)
                        .toUpperCase();
        final var localQueueProof = stateProofManager.getMessageQueueMetadata(remoteLedgerId);
        if (localQueueProof == null) {
            log.debug("{} Local message queue not available for remote ledger {}", ledgerLogPrefix, remoteLedgerId);
            // create new queue and submit it to the local and the remote ledgers
            final var localQueueMetadata = initialMessageQueueMetadata(remoteLedgerId);
            final var localQueueStateProof = buildLocalClprStateProofWrapper(localQueueMetadata);
            log.debug("{} Init new local message queue for remote ledger {}", ledgerLogPrefix, remoteLedgerId);
            localClient.updateMessageQueueMetadata(selfAccount, nodeAccount, remoteLedgerId, localQueueStateProof);
            log.debug("{} Update remote message queue for ledger {}", ledgerLogPrefix, localLedgerId);
            remoteClient.updateMessageQueueMetadata(selfAccount, nodeAccount, localLedgerId, localQueueStateProof);

            // no messages to exchange at this point
            return true;
        }

        final var localQueue = stateProofManager.getLocalMessageQueueMetadata(remoteLedgerId);
        final var remoteQueueProof = remoteClient.getMessageQueueMetadata(localLedgerId);

        if (remoteQueueProof == null) {
            // try to update/init remote message queue
            log.debug("{} Remote message queue not available", ledgerLogPrefix);
            remoteClient.updateMessageQueueMetadata(selfAccount, nodeAccount, localLedgerId, localQueueProof);
            log.debug("{} Sent local message queue to remote", ledgerLogPrefix);
            return false;
        }
        if (!validateStateProof(remoteQueueProof)) {
            log.debug("{} Invalid remote message queue state proof!", ledgerLogPrefix);
            return false;
        }

        final var remoteQueue = extractMessageQueueMetadata(remoteQueueProof);
        long localQueueSentMessageId = localQueue.sentMessageId();

        if (localQueue.receivedMessageId() > remoteQueue.sentMessageId()) {
            log.debug("{} Update remote message queue sent id {}", ledgerLogPrefix, localQueue.receivedMessageId());
            remoteClient.updateMessageQueueMetadata(selfAccount, nodeAccount, localLedgerId, localQueueProof);
        }

        if (remoteQueue.receivedMessageId() > localQueueSentMessageId) {
            // Submit remote Queue (to this ledger) to update the quantity sent msg in state
            localQueueSentMessageId = remoteQueue.receivedMessageId();
            log.debug("{} Update local message queue sent id {}", ledgerLogPrefix, localQueueSentMessageId);
            localClient.updateMessageQueueMetadata(selfAccount, nodeAccount, remoteLedgerId, remoteQueueProof);
        }

        // Push local messages to remote
        if (localQueueSentMessageId + 1 == localQueue.nextMessageId()) {
            log.debug("{} No local messages to publish", ledgerLogPrefix);
        } else {
            // Find which messages to publish
            if (remoteQueue.receivedMessageId() + 1 < localQueue.nextMessageId()) {
                final var firstBundleMessage = bundleFirstMsgId(remoteQueue);
                final var lastQueueMessage = localQueue.nextMessageId() - 1;
                final var lastMessageInBundle = bundleLastMsgId(
                        firstBundleMessage,
                        lastQueueMessage,
                        remoteQueue.bundleShapeOrThrow(),
                        remoteLedgerId,
                        stateProofManager::getMessage);

                final var bundle = createBundle(
                        firstBundleMessage,
                        lastMessageInBundle,
                        localLedgerId,
                        remoteLedgerId,
                        stateProofManager::getMessage);

                if (bundle != null) {
                    log.debug("{} Submit bundle {} - {}", ledgerLogPrefix, firstBundleMessage, lastMessageInBundle);
                    remoteClient.submitProcessMessageBundleTxn(selfAccount, nodeAccount, remoteLedgerId, bundle);
                } else {
                    log.error("{} Error while constructing a bundle!", ledgerLogPrefix);
                }
            }
        }

        // Pull messages from remote
        if (remoteQueue.nextMessageId() - 1 > localQueue.receivedMessageId()) {
            final var fetchedBundle = remoteClient.getMessages(localLedgerId, 5, 6144);
            if (fetchedBundle != null) {
                // TODO: This code is needed only for debug logging. Remove it in future!
                if (fetchedBundle.hasStateProof()) {
                    final var stateProof = fetchedBundle.stateProofOrThrow();
                    final var key = extractMessageKey(stateProof);
                    final var lastIdInBundle = key.messageId();
                    final var firstIdInBundle =
                            lastIdInBundle - fetchedBundle.messages().size();
                    log.debug(
                            "{} Pull and process remote bundle ({} - {})",
                            ledgerLogPrefix,
                            firstIdInBundle,
                            lastIdInBundle);

                    if (lastIdInBundle <= localQueue.receivedMessageId()) {
                        log.debug(
                                "{} Pulled bundle is stale (last received id {})",
                                ledgerLogPrefix,
                                localQueue.receivedMessageId());
                        return false;
                    }
                }
                // process messages from remote
                localClient.submitProcessMessageBundleTxn(selfAccount, nodeAccount, localLedgerId, fetchedBundle);
            } else {
                log.warn("{} Remote queue has messages, but can't fetch bundle", ledgerLogPrefix);
            }
        }

        return true;
    }

    private ClprMessageQueueMetadata initialMessageQueueMetadata(ClprLedgerId remoteLedgerId) {
        return ClprMessageQueueMetadata.newBuilder()
                .ledgerId(remoteLedgerId)
                .nextMessageId(1)
                .sentMessageId(0)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .receivedMessageId(0)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
    }

    private ResponseCodeEnum publishLocalConfigProofToRemote(
            @NonNull final ClprClient remoteClient,
            @NonNull final com.hedera.hapi.node.base.AccountID selfAccount,
            @NonNull final com.hedera.hapi.node.base.AccountID nodeAccountId,
            @NonNull final com.hedera.hapi.block.stream.StateProof localProof) {
        return remoteClient.setConfiguration(selfAccount, nodeAccountId, localProof);
    }

    private com.hedera.hapi.block.stream.StateProof pullRemoteConfigProof(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId) {
        return remoteClient.getConfiguration(remoteLedgerId);
    }

    private boolean isNewerConfig(
            @Nullable final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration existingConfig,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration candidateConfig) {
        if (existingConfig == null) {
            return true;
        }
        final var existingTs = existingConfig.timestampOrElse(Timestamp.DEFAULT);
        final var candidateTs = candidateConfig.timestampOrElse(Timestamp.DEFAULT);
        return candidateTs.seconds() > existingTs.seconds()
                || (candidateTs.seconds() == existingTs.seconds() && candidateTs.nanos() > existingTs.nanos());
    }

    private static boolean isSuccessful(@Nullable final ResponseCodeEnum status) {
        return status == ResponseCodeEnum.OK || status == ResponseCodeEnum.SUCCESS;
    }

    private RemoteEndpoint[] candidateEndpoints(
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration remoteConfig) {
        return remoteConfig.endpoints().stream()
                .filter(org.hiero.hapi.interledger.state.clpr.ClprEndpoint::hasEndpoint)
                .map(endpoint -> {
                    final var accountId = endpoint.nodeAccountIdOrElse(AccountID.DEFAULT);
                    final var resolvedAccountId = accountId.equals(AccountID.DEFAULT) ? null : accountId;
                    return new RemoteEndpoint(endpoint.endpoint(), resolvedAccountId);
                })
                .toArray(RemoteEndpoint[]::new);
    }

    private ClprClient createLocalClient(@NonNull ServiceEndpoint localEndpoint) {
        requireNonNull(localEndpoint);
        try {
            return connectionManager.createClient(localEndpoint);
        } catch (final UnknownHostException e) {
            log.warn(
                    "CLPR Endpoint: Unable to resolve local endpoint {} while storing data for remote ledger",
                    localEndpoint,
                    e);
            return null;
        }
    }
}
