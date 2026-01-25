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
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import org.hiero.hapi.interledger.state.clpr.ClprMessageKey;
import org.hiero.hapi.interledger.state.clpr.ClprMessageValue;
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
        final var txn = context.body();
        final var messageQBundle = txn.clprProcessMessageBundle();
        // TODO: Implement handle!
        //  The full semantics of the handler will be specified in later issues.
        //  For now just save messages in state
        final var messageId = new AtomicInteger(0);
        final var ledgerShortId = new AtomicInteger(0);
        messageQBundle.ifMessageBundle(messageBundle -> {
            messageBundle.messages().forEach(msg -> {
                final var key = ClprMessageKey.newBuilder()
                        .messageId(messageId.getAndIncrement())
                        .ledgerShortId(ledgerShortId.get())
                        .build();
                final var value = ClprMessageValue.newBuilder().payload(msg).build();
                writableMessagesStore.put(key, value);
            });
        });
    }
}
