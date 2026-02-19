// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_LEDGER_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.nextRunningHash;

import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

/**
 * Handles {@code clprEnqueueMessage} internal transactions, appending a single payload into the outbound CLPR message
 * queue for a remote ledger.
 *
 * <p>This transaction exists to ensure outbound queue state mutations are correlated to a transaction in the block
 * stream (for traceability).</p>
 */
public class ClprEnqueueMessageHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(ClprEnqueueMessageHandler.class);
    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprEnqueueMessageHandler(@NonNull final ClprStateProofManager stateProofManager) {
        this.stateProofManager = requireNonNull(stateProofManager);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);

        final var op = context.body().clprEnqueueMessageOrThrow();
        validateTruePreCheck(op.hasLedgerId(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasPayload(), INVALID_TRANSACTION_BODY);

        final var ledgerId = op.ledgerIdOrThrow();
        validateTruePreCheck(ledgerId.ledgerId().length() > 0, CLPR_INVALID_LEDGER_ID);

        final var payload = op.payloadOrThrow();
        validateTruePreCheck(payload.hasMessage() || payload.hasMessageReply(), INVALID_TRANSACTION_BODY);

        final long expectedMessageId = op.expectedMessageId();
        validateTruePreCheck(expectedMessageId >= 0, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        validateFalsePreCheck(context.isUserTransaction(), NOT_SUPPORTED);
        // Internal-only transaction; signature strategy is intentionally undefined.
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        validateTrue(stateProofManager.clprEnabled(), NOT_SUPPORTED);

        final var op = context.body().clprEnqueueMessageOrThrow();
        final var remoteLedgerId = op.ledgerIdOrThrow();
        validateTrue(remoteLedgerId.ledgerId().length() > 0, CLPR_INVALID_LEDGER_ID);

        final var payload = op.payloadOrThrow();
        validateTrue(payload.hasMessage() || payload.hasMessageReply(), INVALID_TRANSACTION_BODY);
        // TEMP-OBSERVABILITY (delete before production): traces enqueue-handler invocation and payload kind.
        log.info(
                "CLPR_OBS|component=clpr_enqueue_message_handler|stage=handle_start|remoteLedgerId={}|expectedMessageId={}|payloadType={}",
                remoteLedgerId.ledgerId(),
                op.expectedMessageId(),
                payload.hasMessage() ? "message" : "messageReply");

        final var queueStore = context.storeFactory().writableStore(WritableClprMessageQueueMetadataStore.class);
        final var messageStore = context.storeFactory().writableStore(WritableClprMessageStore.class);

        final var queueMetadata = queueStore.get(remoteLedgerId);
        validateTrue(queueMetadata != null, CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);

        final long messageId = queueMetadata.nextMessageId();
        validateTrue(messageId >= 1, INVALID_TRANSACTION_BODY);
        // TEMP-OBSERVABILITY (delete before production): traces resolved queue cursor before append.
        log.info(
                "CLPR_OBS|component=clpr_enqueue_message_handler|stage=queue_cursor|nextMessageId={}|receivedMessageId={}",
                messageId,
                queueMetadata.receivedMessageId());

        final long expectedMessageId = op.expectedMessageId();
        if (expectedMessageId != 0) {
            validateTrue(expectedMessageId == messageId, INVALID_TRANSACTION_BODY);
        }

        final var previousHash =
                previousRunningHash(messageStore, queueMetadata.sentRunningHash(), remoteLedgerId, messageId);
        final var nextHash = nextRunningHash(payload, previousHash);
        final var messageKey = ClprMessageKey.newBuilder()
                .ledgerId(remoteLedgerId)
                .messageId(messageId)
                .build();
        final var messageValue = ClprMessageValue.newBuilder()
                .payload(payload)
                .runningHashAfterProcessing(nextHash)
                .build();
        messageStore.put(messageKey, messageValue);
        // TEMP-OBSERVABILITY (delete before production): traces message append details for queue auditability.
        log.info(
                "CLPR_OBS|component=clpr_enqueue_message_handler|stage=message_appended|messageId={}|runningHash={}",
                messageId,
                nextHash);

        final var updatedQueue =
                queueMetadata.copyBuilder().nextMessageId(messageId + 1).build();
        queueStore.put(remoteLedgerId, updatedQueue);
        // TEMP-OBSERVABILITY (delete before production): confirms queue metadata advance after append.
        log.info(
                "CLPR_OBS|component=clpr_enqueue_message_handler|stage=handle_success|newNextMessageId={}",
                messageId + 1);
    }

    private static Bytes previousRunningHash(
            @NonNull final WritableClprMessageStore messageStore,
            @NonNull final Bytes sentRunningHash,
            @NonNull final org.hiero.hapi.interledger.state.clpr.ClprLedgerId remoteLedgerId,
            final long nextMessageId) {
        requireNonNull(messageStore);
        requireNonNull(sentRunningHash);
        requireNonNull(remoteLedgerId);

        final long previousMessageId = nextMessageId - 1;
        if (previousMessageId < 1) {
            return sentRunningHash;
        }
        final var previousMessageKey = ClprMessageKey.newBuilder()
                .ledgerId(remoteLedgerId)
                .messageId(previousMessageId)
                .build();
        final var previousMessage = messageStore.get(previousMessageKey);
        return previousMessage == null ? sentRunningHash : previousMessage.runningHashAfterProcessing();
    }
}
