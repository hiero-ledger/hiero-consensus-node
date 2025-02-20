// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#UNCHECKED_SUBMIT}.
 * <p>
 * Unchecked submit is a mechanism to bypass ingest checks. This is a dangerous operation and will be removed
 * in the future.
 * Transactions that are wrapped in an unchecked submit call are unwrapped during ingest and then submitted directly.
 * Therefore, unchecked submit is not a transaction type that is actually submitted to the network and there is no
 * reason to implement a handler for it. This class exists for completeness but should never actually be called.
 */
@Singleton
public class NetworkUncheckedSubmitHandler implements TransactionHandler {
    @Inject
    public NetworkUncheckedSubmitHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        throw new PreCheckException(NOT_SUPPORTED);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        // nothing to do
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // this will never actually get called
        // because preHandle will always throw
        requireNonNull(context);
        throw new HandleException(NOT_SUPPORTED);
    }

    @NonNull
    @Override
    public Fees calculateFees(final FeeContext feeContext) {
        return Fees.FREE;
    }
}
