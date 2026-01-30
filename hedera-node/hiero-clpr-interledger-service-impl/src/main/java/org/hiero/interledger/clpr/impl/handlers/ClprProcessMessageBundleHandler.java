// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static java.util.Objects.requireNonNull;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageKey;
import static org.hiero.interledger.clpr.ClprStateProofUtils.extractMessageValue;
import static org.hiero.interledger.clpr.ClprStateProofUtils.validateStateProof;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.ConfigProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageReply;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
import org.hiero.interledger.clpr.WritableClprMessageQueueMetadataStore;
import org.hiero.interledger.clpr.WritableClprMessageStore;
import org.hiero.interledger.clpr.impl.ClprStateProofManager;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

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
        if (!stateProofManager.clprEnabled()) {
            throw new PreCheckException(ResponseCodeEnum.NOT_SUPPORTED);
        }
        // TODO: Implement pure checks!
    }

    @Override
    public void preHandle(@NonNull PreHandleContext context) throws PreCheckException {
        if (!stateProofManager.clprEnabled()) {
            throw new PreCheckException(ResponseCodeEnum.NOT_SUPPORTED);
        }
        // TODO: Implement preHandle!
    }

    @Override
    public void handle(@NonNull HandleContext context) throws HandleException {
        final var writableMessagesStore = context.storeFactory().writableStore(WritableClprMessageStore.class);
        final var writableMessageQueueStore =
                context.storeFactory().writableStore(WritableClprMessageQueueMetadataStore.class);

        final var txn = context.body();
        final var messageBundle = txn.clprProcessMessageBundleOrThrow().messageBundleOrThrow();
        final var ledgerId = messageBundle.ledgerIdOrThrow();

        final var messageQueue = writableMessageQueueStore.get(ledgerId);
        final var updatedQueueBuilder = messageQueue.copyBuilder();

        // TODO: Validate running hashes and state proof
        final var stateProof = messageBundle.stateProof();
        if(!validateStateProof(stateProof)) {
            // throw something here
        }

        final var lastMessageValue = extractMessageValue(messageBundle.stateProof());
        final var lastMessageKey = extractMessageKey(messageBundle.stateProof());
        final var lastMessageId = lastMessageKey.messageId();
        final var firstMessageId = lastMessageId - messageBundle.messages().size();

        final var receivedMsgId = new AtomicLong(messageQueue.receivedMessageId());
        final var nextMessageId = new AtomicLong(messageQueue.nextMessageId());

        final var allMessages = new ArrayList<ClprMessagePayload>(messageBundle.messages());
        allMessages.add(lastMessageValue.payload());
        allMessages.forEach(msg -> {
            if (msg.hasMessage()) {
                // create and save reply
                final var id = nextMessageId.getAndIncrement();
                final var key = ClprMessageKey.newBuilder()
                        .messageId(id)
                        .ledgerId(ledgerId)
                        .build();

                final var value = ClprMessageValue.newBuilder()
                        .payload(ClprMessagePayload.newBuilder()
                                .messageReply(ClprMessageReply.newBuilder()
                                        .messageId(receivedMsgId.incrementAndGet()) // TODO: add message id here
                                        .messageReplyData(msg.message().messageData())))
                        .build();
                writableMessagesStore.put(key, value);
            } else {
                // TODO: handle reply
            }
        });

        // update message queue
        updatedQueueBuilder.receivedMessageId(receivedMsgId.getAndIncrement()); // already incremented in the msg key
        updatedQueueBuilder.nextMessageId(nextMessageId.get());
        writableMessageQueueStore.put(ledgerId, updatedQueueBuilder.build());
    }
}
