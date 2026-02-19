// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_BUNDLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_RUNNING_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageKey;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageValue;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.nextRunningHash;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.clpr.ClprHandleMessagePayloadTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

public class ClprProcessMessageBundleHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(ClprProcessMessageBundleHandler.class);
    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprProcessMessageBundleHandler(@NonNull final ClprStateProofManager stateProofManager) {
        this.stateProofManager = requireNonNull(stateProofManager);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        final var body = context.body();
        validateTruePreCheck(body.clprProcessMessageBundleOrThrow().hasMessageBundle(), INVALID_TRANSACTION_BODY);
        final var bundle = body.clprProcessMessageBundleOrThrow().messageBundleOrThrow();

        final var config = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        validateTruePreCheck(bundle.protobufSize() <= config.maxBundleBytes(), CLPR_INVALID_BUNDLE);
        validateTruePreCheck(bundle.messages().size() + 1 <= config.maxBundleMessages(), CLPR_INVALID_BUNDLE);

        // bundle must have at least state proof
        validateTruePreCheck(bundle.hasStateProof(), CLPR_INVALID_STATE_PROOF);

        final var stateProof = bundle.stateProofOrThrow();
        validateTruePreCheck(validateStateProof(stateProof), CLPR_INVALID_STATE_PROOF);

        final var messageQueue = stateProofManager.getLocalMessageQueueMetadata(bundle.ledgerIdOrThrow());
        validateTruePreCheck(messageQueue != null, CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);

        final var receivedMessageId = messageQueue.receivedMessageId();
        final var firstNonProcessedMsgId = receivedMessageId + 1;
        final var lastBundleMessageKey = extractMessageKey(stateProof);
        final var lastBundleMessageId = lastBundleMessageKey.messageId();
        final var firstBundleMessageId = lastBundleMessageId - bundle.messages().size();

        validateFalsePreCheck(lastBundleMessageId <= receivedMessageId, CLPR_INVALID_BUNDLE);
        validateFalsePreCheck(firstBundleMessageId > firstNonProcessedMsgId, CLPR_INVALID_BUNDLE);

        final var skipCount = firstNonProcessedMsgId - firstBundleMessageId;

        final var lastBundleMessageValue = extractMessageValue(stateProof);
        final var lastBundleMessageRunningHash = lastBundleMessageValue.runningHashAfterProcessing();
        final AtomicReference<Bytes> runningHash = new AtomicReference<>(messageQueue.receivedRunningHash());
        bundle.messages().stream()
                .skip(skipCount)
                .forEach(msg -> runningHash.set(nextRunningHash(msg, runningHash.get())));
        runningHash.set(nextRunningHash(lastBundleMessageValue.payload(), runningHash.get()));
        validateTruePreCheck(lastBundleMessageRunningHash.equals(runningHash.get()), CLPR_INVALID_RUNNING_HASH);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        // TODO: Implement preHandle once the payer and required signatures requirements are clear!
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var writableMessageQueueStore =
                context.storeFactory().writableStore(WritableClprMessageQueueMetadataStore.class);

        final var txn = context.body();
        final var bundle = txn.clprProcessMessageBundleOrThrow().messageBundleOrThrow();
        final var sourceLedgerId = bundle.ledgerIdOrThrow();
        // TEMP-OBSERVABILITY (delete before production): traces bundle-handler entry and bundle size.
        log.info(
                "CLPR_OBS|component=clpr_process_message_bundle_handler|stage=handle_start|sourceLedgerId={}|bundleMessages={}",
                sourceLedgerId.ledgerId(),
                bundle.messages().size());

        final var messageQueue = writableMessageQueueStore.get(sourceLedgerId);
        validateTrue(messageQueue != null, CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);

        final var lastBundleMessageKey = extractMessageKey(bundle.stateProofOrThrow());
        final var lastBundleMessageValue = extractMessageValue(bundle.stateProofOrThrow());
        final var lastBundleMessageRunningHash = lastBundleMessageValue.runningHashAfterProcessing();

        final var receivedMessageId = messageQueue.receivedMessageId();
        final var firstNonProcessedMsgId = receivedMessageId + 1;
        final var lastBundleMessageId = lastBundleMessageKey.messageId();
        final var firstBundleMessageId = lastBundleMessageId - bundle.messages().size();
        validateFalse(firstBundleMessageId > firstNonProcessedMsgId, CLPR_INVALID_BUNDLE);

        final var skipCount = firstNonProcessedMsgId - firstBundleMessageId;
        // TEMP-OBSERVABILITY (delete before production): traces bundle replay window and skip calculation.
        log.info(
                "CLPR_OBS|component=clpr_process_message_bundle_handler|stage=bundle_window|receivedMessageId={}|firstBundleMessageId={}|lastBundleMessageId={}|skipCount={}",
                receivedMessageId,
                firstBundleMessageId,
                lastBundleMessageId,
                skipCount);

        final var bundleMessages = new ArrayList<>(bundle.messages());
        bundleMessages.add(lastBundleMessageValue.payload());

        final var receivedMsgId = new AtomicLong(messageQueue.receivedMessageId());
        final int startIndex = Math.toIntExact(skipCount);
        for (int i = startIndex; i < bundleMessages.size(); i++) {
            final var payload = bundleMessages.get(i);
            final var inboundMessageId = receivedMsgId.incrementAndGet();
            dispatchHandlePayload(context, sourceLedgerId, inboundMessageId, payload);
        }

        final var updatedQueueBuilder =
                writableMessageQueueStore.get(sourceLedgerId).copyBuilder();
        updatedQueueBuilder.receivedMessageId(receivedMsgId.get());
        updatedQueueBuilder.receivedRunningHash(lastBundleMessageRunningHash);
        writableMessageQueueStore.put(sourceLedgerId, updatedQueueBuilder.build());
        // TEMP-OBSERVABILITY (delete before production): confirms bundle replay completion and queue cursor advance.
        log.info(
                "CLPR_OBS|component=clpr_process_message_bundle_handler|stage=handle_success|newReceivedMessageId={}|receivedRunningHash={}",
                receivedMsgId.get(),
                lastBundleMessageRunningHash);
    }

    private void dispatchHandlePayload(
            @NonNull final HandleContext context,
            @NonNull final ClprLedgerId sourceLedgerId,
            final long inboundMessageId,
            @NonNull final ClprMessagePayload payload)
            throws HandleException {
        validateTrue(payload.hasMessage() || payload.hasMessageReply(), INVALID_TRANSACTION_BODY);
        final var op = ClprHandleMessagePayloadTransactionBody.newBuilder()
                .sourceLedgerId(sourceLedgerId)
                .inboundMessageId(inboundMessageId)
                .payload(payload)
                .build();
        // TEMP-OBSERVABILITY (delete before production): traces per-payload internal dispatch from bundle handler.
        log.info(
                "CLPR_OBS|component=clpr_process_message_bundle_handler|stage=dispatch_payload|inboundMessageId={}|payloadType={}",
                inboundMessageId,
                payload.hasMessage() ? "message" : "messageReply");
        final var syntheticTxn =
                TransactionBody.newBuilder().clprHandleMessagePayload(op).build();
        final var streamBuilder = context.dispatch(
                stepDispatch(context.payer(), syntheticTxn, StreamBuilder.class, NOOP_SIGNED_TX_CUSTOMIZER));
        // TEMP-OBSERVABILITY (delete before production): traces per-payload dispatch completion status.
        log.info(
                "CLPR_OBS|component=clpr_process_message_bundle_handler|stage=dispatch_payload_result|inboundMessageId={}|status={}",
                inboundMessageId,
                streamBuilder.status());
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
    }
}
