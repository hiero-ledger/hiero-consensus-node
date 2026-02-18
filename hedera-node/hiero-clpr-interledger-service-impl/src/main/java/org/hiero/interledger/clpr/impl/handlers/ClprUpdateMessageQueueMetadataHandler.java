// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;
import static org.hiero.interledger.clpr.impl.ClprServiceImpl.RUNNING_HASH_SIZE;

import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

/**
 * Handles the {@link  org.hiero.hapi.interledger.clpr.ClprUpdateMessageQueueMetadataTransactionBody} to set the
 * message queue metadata of a CLPR ledger.
 * This handler uses the {@link ClprStateProofManager} to validate the state proof and manage ledger's message queue metadata.
 */
public class ClprUpdateMessageQueueMetadataHandler implements TransactionHandler {
    private final ClprStateProofManager stateProofManager;
    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;

    @Inject
    public ClprUpdateMessageQueueMetadataHandler(
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider) {
        this.stateProofManager = requireNonNull(stateProofManager);
        this.networkInfo = requireNonNull(networkInfo);
        this.configProvider = requireNonNull(configProvider);
    }

    @Override
    public void pureChecks(@NonNull PureChecksContext context) throws PreCheckException {
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        final var body = context.body();
        // validate mandatory fields
        validateTruePreCheck(body.clprUpdateMessageQueueMetadataOrThrow().hasLedgerId(), INVALID_TRANSACTION_BODY);
        // ledgerId must exist.
        final var remoteLedgerId = body.clprUpdateMessageQueueMetadataOrThrow().ledgerIdOrThrow();
        validateTruePreCheck(remoteLedgerId.ledgerId().length() > 0, CLPR_INVALID_LEDGER_ID);
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        validateFalsePreCheck(remoteLedgerId.equals(localLedgerId), CLPR_INVALID_LEDGER_ID);
        // validate state proof
        validateTruePreCheck(
                body.clprUpdateMessageQueueMetadataOrThrow().hasMessageQueueMetadataProof(),
                CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);
        final var stateProof = body.clprUpdateMessageQueueMetadataOrThrow().messageQueueMetadataProofOrThrow();
        validateTruePreCheck(validateStateProof(stateProof), CLPR_INVALID_STATE_PROOF);
    }

    @Override
    public void preHandle(@NonNull PreHandleContext context) throws PreCheckException {
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        // TODO: Implement preHandle once the payer and required signatures requirements are clear!
    }

    @Override
    public void handle(@NonNull HandleContext context) throws HandleException {
        final var txn = context.body();
        final var body = txn.clprUpdateMessageQueueMetadataOrThrow();
        final var remoteLedgerId = body.ledgerIdOrThrow();
        final var remoteQueueMetadata =
                ClprStateProofUtils.extractMessageQueueMetadata(body.messageQueueMetadataProofOrThrow());
        final var writableMessageQueueMetadataStore =
                context.storeFactory().writableStore(WritableClprMessageQueueMetadataStore.class);

        final var queueMetadata = writableMessageQueueMetadataStore.get(remoteLedgerId);

        if (queueMetadata == null) {
            // create new queue
            final var initialQueue = initQueue(remoteLedgerId);
            writableMessageQueueMetadataStore.put(remoteLedgerId, initialQueue);
            return;
        }

        final var localLedgerId = stateProofManager.getLocalLedgerId();
        if (localLedgerId != null && !localLedgerId.equals(remoteLedgerId)) {
            final var remoteReceivedId = remoteQueueMetadata.receivedMessageId();
            final var remoteReceivedRunningHash = remoteQueueMetadata.receivedRunningHash();

            final var maxMessageId = queueMetadata.nextMessageId() - 1;
            final var lastProcessedId = Math.min(remoteReceivedId, maxMessageId);
            if (lastProcessedId > queueMetadata.sentMessageId()) {
                final var writableMessageStore = context.storeFactory().writableStore(WritableClprMessageStore.class);
                final var lastMessageKey = ClprMessageKey.newBuilder()
                        .messageId(lastProcessedId)
                        .ledgerId(remoteLedgerId)
                        .build();
                final var lastMessageValue = writableMessageStore.get(lastMessageKey);
                final var lastRunningHash = lastMessageValue == null
                        ? queueMetadata.sentRunningHash()
                        : lastMessageValue.runningHashAfterProcessing();

                // TODO(CLPR-PROD-HARDENING): This per-message removal loop is intentionally kept simple for the
                // prototype. For large backlog windows this becomes O(n) work in one transaction and should be
                // replaced with bounded/chunked cleanup semantics during production hardening.
                for (long id = queueMetadata.sentMessageId() + 1; id <= lastProcessedId; id++) {
                    final var messageKey = ClprMessageKey.newBuilder()
                            .messageId(id)
                            .ledgerId(remoteLedgerId)
                            .build();
                    writableMessageStore.remove(messageKey);
                }

                final var updatedQueueMetadata = queueMetadata
                        .copyBuilder()
                        .sentMessageId(lastProcessedId)
                        .sentRunningHash(
                                lastProcessedId == remoteReceivedId ? remoteReceivedRunningHash : lastRunningHash)
                        .build();

                writableMessageQueueMetadataStore.put(remoteLedgerId, updatedQueueMetadata);
            }
        }
    }

    private ClprMessageQueueMetadata initQueue(ClprLedgerId remoteLedgerId) {
        return ClprMessageQueueMetadata.newBuilder()
                .ledgerId(remoteLedgerId)
                .nextMessageId(1)
                .sentMessageId(0)
                .sentRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .receivedMessageId(0)
                .receivedRunningHash(Bytes.wrap(new byte[RUNNING_HASH_SIZE]))
                .build();
    }
}
