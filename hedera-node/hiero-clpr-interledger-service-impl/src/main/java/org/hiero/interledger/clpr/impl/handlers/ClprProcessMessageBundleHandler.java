// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_BUNDLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_RUNNING_HASH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_MESSAGE_QUEUE_NOT_AVAILABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageKey;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageValue;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;
import static org.hiero.interledger.clpr.impl.ClprMessageUtils.nextRunningHash;
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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageReply;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

public class ClprProcessMessageBundleHandler implements TransactionHandler {

    private final ClprStateProofManager stateProofManager;
    private final NetworkInfo networkInfo;
    private final ConfigProvider configProvider;

    @Inject
    public ClprProcessMessageBundleHandler(
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
        validateTruePreCheck(body.clprProcessMessageBundleOrThrow().hasMessageBundle(), INVALID_TRANSACTION_BODY);
        final var bundle = body.clprProcessMessageBundleOrThrow().messageBundleOrThrow();
        // bundle must have at least state proof
        validateTruePreCheck(bundle.hasStateProof(), CLPR_INVALID_STATE_PROOF);
        // validate the state proof
        final var stateProof = bundle.stateProofOrThrow();
        validateTruePreCheck(validateStateProof(stateProof), CLPR_INVALID_STATE_PROOF);

        final var messageQueue = stateProofManager.getLocalMessageQueueMetadata(bundle.ledgerIdOrThrow());
        validateTruePreCheck(messageQueue != null, CLPR_MESSAGE_QUEUE_NOT_AVAILABLE);

        final var receivedMessageId = messageQueue.receivedMessageId();
        final var firstNonProcessedMsgId = receivedMessageId + 1;
        final var lastBundleMessageKey = extractMessageKey(stateProof);
        final var lastBundleMessageId = lastBundleMessageKey.messageId();
        final var firstBundleMessageId = lastBundleMessageId - bundle.messages().size();

        // validate if the bundle has misaligned message ids or all messages in the bundle are already processed
        validateFalsePreCheck(lastBundleMessageId <= receivedMessageId, CLPR_INVALID_BUNDLE);
        validateFalsePreCheck(firstBundleMessageId > firstNonProcessedMsgId, CLPR_INVALID_BUNDLE);

        // we may have already received a part of this bundle (or entire bundle).
        // In this case we should skip processing these messages
        final var skipCount = firstNonProcessedMsgId - firstBundleMessageId;

        final var lastBundleMessageValue = extractMessageValue(stateProof);
        final var lastBundleMessageRunningHash = lastBundleMessageValue.runningHashAfterProcessing();
        AtomicReference<Bytes> runningHash = new AtomicReference<>(messageQueue.receivedRunningHash());
        bundle.messages().stream()
                .skip(skipCount)
                .forEach(msg -> runningHash.set(nextRunningHash(msg, runningHash.get())));
        runningHash.set(nextRunningHash(lastBundleMessageValue.payload(), runningHash.get()));
        validateTruePreCheck(lastBundleMessageRunningHash.equals(runningHash.get()), CLPR_INVALID_RUNNING_HASH);
    }

    @Override
    public void preHandle(@NonNull PreHandleContext context) throws PreCheckException {
        validateTruePreCheck(stateProofManager.clprEnabled(), NOT_SUPPORTED);
        // TODO: Implement preHandle once the payer and required signatures requirements are clear!
    }

    @Override
    public void handle(@NonNull HandleContext context) throws HandleException {
        final var writableMessagesStore = context.storeFactory().writableStore(WritableClprMessageStore.class);
        final var writableMessageQueueStore =
                context.storeFactory().writableStore(WritableClprMessageQueueMetadataStore.class);

        final var txn = context.body();
        final var bundle = txn.clprProcessMessageBundleOrThrow().messageBundleOrThrow();
        final var ledgerId = bundle.ledgerIdOrThrow();

        final var messageQueue = writableMessageQueueStore.get(ledgerId);
        final var updatedQueueBuilder = messageQueue.copyBuilder();

        final var lastBundleMessageKey = extractMessageKey(bundle.stateProofOrThrow());
        final var lastBundleMessageValue = extractMessageValue(bundle.stateProofOrThrow());
        final var lastBundleMessageRunningHash = lastBundleMessageValue.runningHashAfterProcessing();

        final var receivedMessageId = messageQueue.receivedMessageId();
        final var firstNonProcessedMsgId = receivedMessageId + 1;
        final var lastBundleMessageId = lastBundleMessageKey.messageId();
        final var firstBundleMessageId = lastBundleMessageId - bundle.messages().size();

        // validate if there are missing messages between the last received and the first in this bundle
        validateFalse(firstBundleMessageId > firstNonProcessedMsgId, CLPR_INVALID_BUNDLE);

        // we may have already received a part of this bundle (or entire bundle).
        // In this case we should skip processing these messages
        final var skipCount = firstNonProcessedMsgId - firstBundleMessageId;

        // 1. Handle all messages (including the last message, extracted from the state proof)
        final var bundleMessages = new ArrayList<>(bundle.messages());
        bundleMessages.add(lastBundleMessageValue.payload());

        final var receivedMsgId = new AtomicLong(messageQueue.receivedMessageId());
        final var nextMessageId = new AtomicLong(messageQueue.nextMessageId());

        // Use last message's running hash for initial hash of the reply.
        final var lastQueuedMessageKey = ClprMessageKey.newBuilder()
                .messageId(messageQueue.nextMessageId() - 1)
                .ledgerId(ledgerId)
                .build();
        final var lastQueuedMessage = writableMessagesStore.get(lastQueuedMessageKey);
        final var initHash = lastQueuedMessage == null
                ? Bytes.wrap(new byte[RUNNING_HASH_SIZE])
                : lastQueuedMessage.runningHashAfterProcessing();
        AtomicReference<Bytes> replyRunningHash = new AtomicReference<>(initHash);

        bundleMessages.stream().skip(skipCount).forEach(msg -> {
            // create and save msg reply
            if (msg.hasMessage()) {
                final var id = nextMessageId.getAndIncrement();
                final var key = ClprMessageKey.newBuilder()
                        .messageId(id)
                        .ledgerId(ledgerId)
                        .build();

                final var payload = ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder()
                                .messageId(receivedMsgId.incrementAndGet())
                                // TODO: Handle message data and generate actual reply data
                                .messageReplyData(msg.messageOrThrow().messageData()))
                        .build();
                final var lastRunningHash = replyRunningHash.get();
                replyRunningHash.set(nextRunningHash(payload, lastRunningHash));

                final var value = ClprMessageValue.newBuilder()
                        .runningHashAfterProcessing(replyRunningHash.get())
                        .payload(payload)
                        .build();
                writableMessagesStore.put(key, value);

            } else {
                receivedMsgId.getAndIncrement();
                // TODO: handle msg reply data
            }
        });

        // update message queue
        updatedQueueBuilder.receivedMessageId(receivedMsgId.get());
        updatedQueueBuilder.receivedRunningHash(lastBundleMessageRunningHash);
        updatedQueueBuilder.nextMessageId(nextMessageId.get());
        writableMessageQueueStore.put(ledgerId, updatedQueueBuilder.build());
    }
}
