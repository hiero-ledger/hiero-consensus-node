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
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import org.hiero.interledger.clpr.ClprStateProofUtils;
import org.hiero.interledger.clpr.ReadableClprMessageQueueStore;
import org.hiero.interledger.clpr.WritableClprMessageQueueStore;
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
        final var txn = context.body();
        final var body = txn.clprUpdateMessageQueueMetadata();
        final var messageQueueMetadata =
                ClprStateProofUtils.extractMessageQueueMetadata(body.messageQueueMetadataProof());
        final var writableMessageQueueMetadataStore =
                context.storeFactory().writableStore(WritableClprMessageQueueStore.class);
        // try to update the state
        writableMessageQueueMetadataStore.put(body.ledgerId(), messageQueueMetadata);

        final var readableMessageQueueMetadataSotre =
                context.storeFactory().readableStore(ReadableClprMessageQueueStore.class);
        // try to find it
        final var metadata = readableMessageQueueMetadataSotre.get(body.ledgerId());
        final var test = metadata.toString();
    }
}
