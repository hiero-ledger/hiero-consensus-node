// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.SiblingNode;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.node.app.hapi.utils.blocks.HashUtils;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.GrpcConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.UnknownHostException;
import java.security.MessageDigest;
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

    private record QueueMetadata(boolean hasMessages) {}

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
            final var localConfig = stateProofManager.readLedgerConfiguration(localLedgerId);
            if (localConfig == null) {
                log.warn(
                        "CLPR endpoint maintenance found no local configuration for ledger {}; ledgerId bytes={}",
                        localLedgerId,
                        localLedgerId.ledgerId());
                final var allConfigs = stateProofManager.readAllLedgerConfigurations();
                log.warn("CLPR endpoint maintenance: all config keys={}", allConfigs.keySet());
                for (final var entry : allConfigs.entrySet()) {
                    log.warn(
                            "CLPR endpoint maintenance: config key={} ledgerIdBytes={}",
                            entry.getKey(),
                            entry.getKey().ledgerId());
                }
                return;
            }
            final var localProof = stateProofManager.getLedgerConfiguration(localLedgerId);
            if (localProof == null) {
                log.debug("CLPR endpoint maintenance skipped; no state proof available for ledger {}", localLedgerId);
                return;
            } else {
                // --- TSS DEBUG: log all three inputs to TSS.verifyTSS ---
                final var proof = requireNonNull(localProof);
                final var sigBytes =
                        proof.hasSignedBlockProof() && proof.signedBlockProof().blockSignature() != null
                                ? proof.signedBlockProof().blockSignature()
                                : Bytes.EMPTY;
                try {
                    final byte[] rootHash = StateProofVerifier.debugComputeRootHash(proof);
                    final var ledgerIdBytes = localLedgerId.ledgerId();
//                    log.warn(
//                            "TSS_DEBUG inputs: ledgerId={} (len={}), rootHash={} (len={}), sigLen={}",
//                            ledgerIdBytes.toHex(),
//                            ledgerIdBytes.length(),
//                            Bytes.wrap(rootHash).toHex(),
//                            rootHash.length,
//                            sigBytes.length());
                    // Step-by-step hash walk to find divergence point
//                    debugWalkProofHashes(proof);
                } catch (final Exception e) {
//                    log.warn("TSS_DEBUG: rootHash computation THREW: {}", e.toString());
                }
                // Real verification via stateProofManager
                final boolean isValid = stateProofManager.verifyProof(proof, localLedgerId.ledgerId());
                if (!isValid) {
                    log.warn("Found invalid state proof for local ledger {}; skipping this cycle", localLedgerId);
                    return;
                }
            }
            final var selfNodeInfo = networkInfo.selfNodeInfo();
            final var localEndpoint = localServiceEndpoint();
            final var configsByLedgerId = stateProofManager.readAllLedgerConfigurations();
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
                    remoteEndpoint, selfNodeInfo, localEndpoint, localProof, remoteLedgerId, storedRemoteConfig)) {
                cursor.set(Math.floorMod(index + 1, size));
                return;
            }
        }
        cursor.set(Math.floorMod(startIndex + maxAttempts, size));
    }

    private boolean exchangeWithRemoteEndpoint(
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
            @NonNull final AccountID selfAccount,
            @NonNull final AccountID nodeAccountId,
            @NonNull final ServiceEndpoint localEndpoint,
            @NonNull final com.hedera.hapi.block.stream.StateProof localProof,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerConfiguration storedRemoteConfig,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
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

        final var queueMetadata = pushPullQueueMetadata(remoteClient, remoteLedgerId, remoteServiceEndpoint);
        if (queueMetadata == null) {
            return false;
        }
        if (!queueMetadata.hasMessages()) {
            return true;
        }

        final var queueContentSucceeded =
                pushPullQueueContent(remoteClient, remoteLedgerId, queueMetadata, remoteServiceEndpoint);
        return queueContentSucceeded;
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

    private @Nullable QueueMetadata pushPullQueueMetadata(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        final var pushSuccess = pushQueueMetadata(remoteClient, remoteLedgerId, remoteServiceEndpoint);
        if (!pushSuccess) {
            return null;
        }
        return pullQueueMetadata(remoteClient, remoteLedgerId, remoteServiceEndpoint);
    }

    private boolean pushPullQueueContent(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final QueueMetadata queueMetadata,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        final var pushSuccess = pushQueueContent(remoteClient, remoteLedgerId, queueMetadata, remoteServiceEndpoint);
        if (!pushSuccess) {
            return false;
        }
        return pullQueueContent(remoteClient, remoteLedgerId, queueMetadata, remoteServiceEndpoint);
    }

    private boolean pushQueueMetadata(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        // TODO: implement queue metadata publish/pull protocol.
        return true;
    }

    private @Nullable QueueMetadata pullQueueMetadata(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        // TODO: implement queue metadata publish/pull protocol.
        return new QueueMetadata(false);
    }

    private boolean pushQueueContent(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final QueueMetadata queueMetadata,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        // TODO: implement queue content publish/pull protocol.
        return true;
    }

    private boolean pullQueueContent(
            @NonNull final ClprClient remoteClient,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            @NonNull final QueueMetadata queueMetadata,
            @NonNull final ServiceEndpoint remoteServiceEndpoint) {
        // TODO: implement queue content publish/pull protocol.
        return true;
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

    /**
     * DEBUG: Walks through every MerklePath in the proof, computing and logging the intermediate
     * hash at each sibling step. This pinpoints exactly where the hash chain diverges from the
     * expected block root hash.
     */
    @SuppressWarnings("java:S3776") // complexity acceptable for temporary debug method
    private static void debugWalkProofHashes(@NonNull final StateProof proof) {
        final var paths = proof.paths();
//        log.warn("TSS_DEBUG_WALK: proof has {} path(s)", paths.size());

        final MessageDigest digest = sha384DigestOrThrow();
        // Stores computed hashes for each path index, keyed by path index
        final byte[][] pathResults = new byte[paths.size()][];

        for (int pathIdx = 0; pathIdx < paths.size(); pathIdx++) {
            final MerklePath path = paths.get(pathIdx);
            final var siblings = path.siblings();
//            log.warn(
//                    "TSS_DEBUG_WALK: path[{}] nextPathIndex={}, siblings={}, hasHash={}, hasStateItemLeaf={}, hasTimestampLeaf={}, hasBlockItemLeaf={}",
//                    pathIdx,
//                    path.nextPathIndex(),
//                    siblings.size(),
//                    path.hasHash(),
//                    path.hasStateItemLeaf(),
//                    path.hasTimestampLeaf(),
//                    path.hasBlockItemLeaf());

            byte[] current;

            // --- Determine the base hash for this path ---
            if (path.hasStateItemLeaf()) {
                current = HashUtils.computeStateItemLeafHash(digest, path.stateItemLeaf());
//                log.warn(
//                        "TSS_DEBUG_WALK: path[{}] base=stateItemLeaf hash={} leafLen={}",
//                        pathIdx,
//                        Bytes.wrap(current).toHex(),
//                        path.stateItemLeaf().length());
            } else if (path.hasBlockItemLeaf()) {
                current = HashUtils.computeBlockItemLeafHash(digest, path.blockItemLeaf());
//                log.warn(
//                        "TSS_DEBUG_WALK: path[{}] base=blockItemLeaf hash={}",
//                        pathIdx,
//                        Bytes.wrap(current).toHex());
            } else if (path.hasTimestampLeaf()) {
                current = HashUtils.computeTimestampLeafHash(digest, path.timestampLeaf());
//                log.warn(
//                        "TSS_DEBUG_WALK: path[{}] base=timestampLeaf hash={}",
//                        pathIdx,
//                        Bytes.wrap(current).toHex());
            } else if (path.hasHash()) {
                current = path.hash().toByteArray();
//                log.warn(
//                        "TSS_DEBUG_WALK: path[{}] base=explicitHash hash={}",
//                        pathIdx,
//                        Bytes.wrap(current).toHex());
            } else {
                // Internal-only path — needs child hashes from the stack
                // Collect children that point to this path
                final java.util.List<byte[]> childHashes = new java.util.ArrayList<>();
                for (int ci = 0; ci < pathIdx; ci++) {
                    if (paths.get(ci).nextPathIndex() == pathIdx && pathResults[ci] != null) {
                        childHashes.add(pathResults[ci]);
                    }
                }
//                log.warn(
//                        "TSS_DEBUG_WALK: path[{}] base=internalNode, {} child(ren) pointing here",
//                        pathIdx,
//                        childHashes.size());

                if (childHashes.size() == 1) {
                    current = HashUtils.computeSingleChildHash(digest, childHashes.get(0));
//                    log.warn(
//                            "TSS_DEBUG_WALK: path[{}] singleChild hash={}",
//                            pathIdx,
//                            Bytes.wrap(current).toHex());
                } else if (childHashes.size() == 2) {
                    current = HashUtils.joinHashes(digest, childHashes.get(0), childHashes.get(1));
                    log.warn(
                            "TSS_DEBUG_WALK: path[{}] joinChildren hash={}",
                            pathIdx,
                            Bytes.wrap(current).toHex());
                } else {
                    log.warn(
                            "TSS_DEBUG_WALK: path[{}] ERROR: expected 1 or 2 children, got {}",
                            pathIdx,
                            childHashes.size());
                    return;
                }
            }

            // --- Walk through siblings, logging each step ---
            for (int sibIdx = 0; sibIdx < siblings.size(); sibIdx++) {
                final SiblingNode sibling = siblings.get(sibIdx);
                final byte[] siblingBytes = sibling.hash().toByteArray();

                final String label;
                if (siblingBytes.length == 0) {
                    current = HashUtils.computeSingleChildHash(digest, current);
                    label = "singleChild";
                } else if (sibling.isLeft()) {
                    current = HashUtils.joinHashes(digest, siblingBytes, current);
                    label = "join(LEFT_sibling, current)";
                } else {
                    current = HashUtils.joinHashes(digest, current, siblingBytes);
                    label = "join(current, RIGHT_sibling)";
                }
                log.warn(
                        "TSS_DEBUG_WALK: path[{}] sib[{}/{}] {} isLeft={} sibHash={} -> intermediate={}",
                        pathIdx,
                        sibIdx,
                        siblings.size() - 1,
                        label,
                        sibling.isLeft(),
                        siblingBytes.length == 0
                                ? "<empty>"
                                : Bytes.wrap(siblingBytes).toHex(),
                        Bytes.wrap(current).toHex());
            }

            pathResults[pathIdx] = current;
            log.warn(
                    "TSS_DEBUG_WALK: path[{}] FINAL hash={} (nextPathIndex={})",
                    pathIdx,
                    Bytes.wrap(current).toHex(),
                    path.nextPathIndex());
        }

        // The root hash is the result of the last path with nextPathIndex=-1
        for (int i = paths.size() - 1; i >= 0; i--) {
            if (paths.get(i).nextPathIndex() == -1 && pathResults[i] != null) {
                log.warn(
                        "TSS_DEBUG_WALK: ROOT hash={}",
                        Bytes.wrap(pathResults[i]).toHex());
                break;
            }
        }
    }
}
