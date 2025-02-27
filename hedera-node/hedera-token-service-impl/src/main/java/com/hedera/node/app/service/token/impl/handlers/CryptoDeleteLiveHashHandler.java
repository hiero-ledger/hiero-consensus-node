// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WorkflowException;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#CRYPTO_DELETE_LIVE_HASH}.
 *
 * This transaction type is not currently supported. It is reserved for future use.
 */
@Singleton
public class CryptoDeleteLiveHashHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public CryptoDeleteLiveHashHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        requireNonNull(context);
        throw new WorkflowException(ResponseCodeEnum.NOT_SUPPORTED);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext txn) {
        // nothing to do
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws WorkflowException {
        // this will never actually get called
        // because preHandle will throw a WorkflowException
        // before we get here
        throw new WorkflowException(ResponseCodeEnum.NOT_SUPPORTED);
    }
}
