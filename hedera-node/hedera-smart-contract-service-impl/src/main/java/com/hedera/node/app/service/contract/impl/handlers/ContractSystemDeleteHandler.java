// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WorkflowException;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#SYSTEM_DELETE}.
 * <p>
 * This transaction type has been deprecated. Because protobufs promise backwards compatibility,
 * we cannot remove it. However, it should not be used.
 */
@Singleton
public class ContractSystemDeleteHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractSystemDeleteHandler() {
        // Exists for injection
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) {
        // this will never actually get called
        // because pureChecks will always throw
        throw new WorkflowException(NOT_SUPPORTED);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) {
        throw new WorkflowException(NOT_SUPPORTED);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws WorkflowException {
        // this will never actually get called
        // because pureChecks will always throw
        throw new WorkflowException(NOT_SUPPORTED);
    }

    @NonNull
    @Override
    public Fees calculateFees(final FeeContext feeContext) {
        return Fees.FREE;
    }
}
