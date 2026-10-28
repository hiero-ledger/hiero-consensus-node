// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprSubmitBundleTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * <p>Because this node pays for each submission, every bundle is first checked by
 * {@link ClprBundlePrevalidator}. A bundle that consensus would reject is dropped before
 * it is gossiped, so the node pays neither the transaction fee nor the endpoint
 * misbehavior penalty for it.
 */
@Singleton
public class ClprBundleSubmitter {
    private static final Logger logger = LogManager.getLogger(ClprBundleSubmitter.class);

    private final AppContext appContext;
    private final ConfigProvider configProvider;
    private final ClprBundlePrevalidator prevalidator;
    private final AtomicReference<Instant> lastUsedInstant = new AtomicReference<>(Instant.MIN);

    @Inject
    public ClprBundleSubmitter(
            @NonNull final AppContext appContext,
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprBundlePrevalidator prevalidator) {
        this.appContext = requireNonNull(appContext);
        this.configProvider = requireNonNull(configProvider);
        this.prevalidator = requireNonNull(prevalidator);
    }

    /**
     * Prevalidates a received sync payload and, if it passes, submits it as a {@code ClprSubmitBundle} HAPI
     * transaction paid by this node.
     *
     * <p>Nothing is submitted when CLPR is disabled, the payload is empty, gossip is unavailable, or
     * {@link ClprBundlePrevalidator#validate} rejects the bundle. A bundle rejected only with
     * {@code CLPR_NO_PROGRESS}, such as an idle peer's pure acknowledgement, is skipped but reported
     * as a success, because the exchange itself did not fail. Prevalidation fails closed: an
     * unexpected error while validating is reported as a failure, and nothing is submitted.
     *
     * @param payload the received sync payload from the peer
     * @return {@code true} if the bundle was submitted, or was verified and needs no transaction;
     *     {@code false} otherwise
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

        try {
            prevalidator.validate(payload, configuration);
        } catch (final PreCheckException e) {
            // Idle peers legitimately send pure acknowledgements. Skip their transaction without
            // treating the exchange as a peer failure or paying for a NoProgress consensus rejection.
            if (e.responseCode() == CLPR_NO_PROGRESS) {
                logger.debug("[CLPR-SUBMIT] skipped no-progress inbound bundle conn={}", payload.channelId());
                return true;
            }
            logger.warn(
                    "[CLPR-SUBMIT] rejected inbound bundle before submission conn={} status={}",
                    payload.channelId(),
                    e.responseCode());
            return false;
        } catch (final RuntimeException e) {
            // Verification must fail closed: no node-paid transaction when local validation is unavailable.
            logger.warn("[CLPR-SUBMIT] could not validate inbound bundle conn={}", payload.channelId(), e);
            return false;
        }

        final var selfNode = appContext.selfNodeInfoSupplier().get();
        final var selfAccountId = selfNode.accountId();
        // Guarantee strictly monotonic valid-start timestamps: if the wall clock hasn't
        // advanced past the last-used instant (same nanosecond on fast hardware, or a
        // backward clock step), nudge by 1 ns so no two submissions share a TransactionID.
        final Instant now = lastUsedInstant.updateAndGet(last -> {
            final Instant candidate = appContext.instantSource().instant();
            return candidate.isAfter(last) ? candidate : last.plusNanos(1);
        });
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
