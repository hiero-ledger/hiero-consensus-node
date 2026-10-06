// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Constructs and submits {@code ClprSubmitBundle} HAPI transactions from received
 * sync payloads. Used by both the sync initiator (ClprChannelManager) and the
 * sync responder (ClprSyncWorkflowImpl) to submit inbound bundles for consensus.
 *
 * <p>Submission goes through {@link AppContext.Gossip#submit(TransactionBody)} —
 * the same path used by {@code TssSubmissions} and {@code HintsSubmissions}. The
 * transaction is wrapped in a {@code SignedTransaction} with an empty
 * {@code SignatureMap}; identity is established by the event-level signature
 * applied when the platform gossips the consensus event. {@code ClprSubmitBundle}
 * must be present in {@code networkAdmin.nodeTransactionsAllowList} for this to
 * succeed (it is, by default).
 */
@Singleton
public class ClprBundleSubmitter {
    private static final Logger logger = LogManager.getLogger(ClprBundleSubmitter.class);

    private final AppContext appContext;
    private final ConfigProvider configProvider;

    @Inject
    public ClprBundleSubmitter(@NonNull final AppContext appContext, @NonNull final ConfigProvider configProvider) {
        this.appContext = requireNonNull(appContext);
        this.configProvider = requireNonNull(configProvider);
    }

    /**
     * Submits a received sync payload as a {@code ClprSubmitBundle} HAPI transaction.
     *
     * @param payload the received sync payload from the peer
     * @return true if submission succeeded, false otherwise
     */
    public boolean submitBundle(@NonNull final ClprSyncPayload payload) {
        requireNonNull(payload);
        final var configuration = configProvider.getConfiguration();
        final var clprConfig = configuration.getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            return false;
        }
        logger.debug(
                "[CLPR-SUBMIT] submitBundle enter conn={} bundleBytes={}",
                payload.channelId(),
                payload.bundlePayload().length());

        if (payload.bundlePayload().length() == 0) {
            logger.debug("[CLPR-SUBMIT] empty bundle; skipping conn={}", payload.channelId());
            return false;
        }

        if (!appContext.gossip().isAvailable()) {
            logger.warn("Gossip not available, skipping bundle submission");
            return false;
        }

        final var selfNode = appContext.selfNodeInfoSupplier().get();
        final var selfAccountId = selfNode.accountId();
        final var now = Instant.now();
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        final var validDuration = new Duration(hederaConfig.transactionMaxValidDuration());

        final var clprOp = ClprSubmitBundleTransactionBody.newBuilder()
                .channelId(payload.channelId())
                .bundlePayload(payload.bundlePayload())
                .endpointNodeId(selfNode.nodeId())
                .build();

        final var txBody = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(selfAccountId)
                        .transactionValidStart(Timestamp.newBuilder()
                                .seconds(now.getEpochSecond())
                                .nanos(now.getNano())
                                .build())
                        .nonce(0)
                        .build())
                .nodeAccountID(selfAccountId)
                .transactionFee(clprConfig.nodeSubmitBundleMaxFee())
                .transactionValidDuration(validDuration)
                .clprSubmitBundle(clprOp)
                .build();

        try {
            logger.debug(
                    "[CLPR-SUBMIT] gossip submit attempt conn={} endpointNode={} payer={} validStart={} "
                            + "bundleBytes={}",
                    payload.channelId(),
                    selfNode.nodeId(),
                    selfAccountId,
                    txBody.transactionIDOrThrow().transactionValidStart(),
                    payload.bundlePayload().length());
            appContext.gossip().submit(txBody);
            logger.debug(
                    "[CLPR-SUBMIT] gossip submit accepted conn={} endpointNode={} payer={} bundleBytes={}",
                    payload.channelId(),
                    selfNode.nodeId(),
                    selfAccountId,
                    payload.bundlePayload().length());
            return true;
        } catch (final IllegalArgumentException e) {
            // Hedera.submit translates allowList rejection / unknown functionality
            // / duplicate-transaction into IllegalArgumentException. Treat as a
            // soft failure — the orchestrator will retry on its next sync tick.
            logger.warn("[ClprSubmit] FAILED ClprSubmitBundle for conn={}: {}", payload.channelId(), e.getMessage());
            return false;
        } catch (final IllegalStateException e) {
            // Platform not active or other transient state issue.
            logger.warn(
                    "[ClprSubmit] could not submit ClprSubmitBundle for conn={} (transient): {}",
                    payload.channelId(),
                    e.getMessage());
            return false;
        }
    }
}
