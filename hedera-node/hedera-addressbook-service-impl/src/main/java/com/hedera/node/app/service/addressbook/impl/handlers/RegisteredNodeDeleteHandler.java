// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.AccountsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Workflow-related functionality regarding {@link HederaFunctionality#REGISTERED_NODE_DELETE}.
 */
@Singleton
public class RegisteredNodeDeleteHandler implements TransactionHandler {
    @Inject
    public RegisteredNodeDeleteHandler() {
        // exists for injection
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().registeredNodeDeleteOrThrow();
        validateFalsePreCheck(op.registeredNodeId() < 0, INVALID_NODE_ID);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().registeredNodeDeleteOrThrow();
        final var accountConfig = context.configuration().getConfigData(AccountsConfig.class);
        final var store = context.createStore(ReadableRegisteredNodeStore.class);
        final var existing = store.get(op.registeredNodeId());
        validateFalsePreCheck(existing == null, INVALID_NODE_ID);

        final var payerNum = context.payer().accountNum();
        if (payerNum != accountConfig.treasury()
                && payerNum != accountConfig.systemAdmin()
                && payerNum != accountConfig.addressBookAdmin()) {
            context.requireKeyOrThrow(existing.adminKey(), INVALID_ADMIN_KEY);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);
        final var op = context.body().registeredNodeDeleteOrThrow();

        final var storeFactory = context.storeFactory();
        final var registeredNodeStore = storeFactory.writableStore(WritableRegisteredNodeStore.class);
        final var nodeStore = storeFactory.readableStore(ReadableNodeStore.class);

        final var existing = registeredNodeStore.get(op.registeredNodeId());
        validateFalse(existing == null, INVALID_NODE_ID);

        // Forbid deletion while referenced by any consensus node.
        for (final var nodeKey : nodeStore.keys()) {
            final var node = nodeStore.get(nodeKey.number());
            if (node != null && node.associatedRegisteredNode().contains(op.registeredNodeId())) {
                throw new com.hedera.node.app.spi.workflows.HandleException(ENTITY_NOT_ALLOWED_TO_DELETE);
            }
        }

        registeredNodeStore.remove(op.registeredNodeId());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
        return calculator.calculate();
    }
}
