// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
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
    private static final Logger log = LogManager.getLogger(ClprUpdateMessageQueueMetadataHandler.class);
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
        // TODO: Implement actual handle
        final var txn = context.body();
        final var body = txn.clprUpdateMessageQueueMetadata();
        final var txnLedgerId = body.ledgerId();
        final var messageQueueMetadata =
                ClprStateProofUtils.extractMessageQueueMetadata(body.messageQueueMetadataProof());
        final var writableMessageQueueMetadataStore =
                context.storeFactory().writableStore(WritableClprMessageQueueMetadataStore.class);

        // TODO: REMOVE THIS TEST CODE
        // if this transaction is updating REMOTE ledger queue,
        // generate messages for the remote ledger (only for testing)
        final var localLedgerId = stateProofManager.getLocalLedgerId();
        if (localLedgerId != null && !localLedgerId.equals(txnLedgerId)) {
            final var updatedMetadata = generateOutgoingMsg(context, txnLedgerId, messageQueueMetadata);
            writableMessageQueueMetadataStore.put(txnLedgerId, updatedMetadata);
        }
    }

    // TODO: REMOVE THIS TEST CODE
    private ClprMessageQueueMetadata generateOutgoingMsg(
            HandleContext context, ClprLedgerId remoteLedgerId, ClprMessageQueueMetadata metadata) {

        final var nexId = metadata.nextMessageId();
        if (nexId < 6) {
            final var messageStore = context.storeFactory().writableStore(WritableClprMessageStore.class);

            final var msgString = "Message ID: %d; Ledger ID: %s".formatted(nexId, remoteLedgerId);
            final var messageKey = ClprMessageKey.newBuilder()
                    .messageId(nexId)
                    .ledgerId(remoteLedgerId)
                    .build();
            final var payload = ClprMessagePayload.newBuilder()
                    .message(ClprMessage.newBuilder()
                            .messageData(Bytes.wrap(msgString.getBytes()))
                            .build())
                    .build();
            final var messageValue =
                    ClprMessageValue.newBuilder().payload(payload).build();
            messageStore.put(messageKey, messageValue);

            return metadata.copyBuilder().nextMessageId(1).build();
        }

        return metadata;
    }
}
