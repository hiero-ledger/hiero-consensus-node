// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.node.app.service.addressbook.impl.validators.RegisteredNodeValidator.validateDescription;
import static com.hedera.node.app.service.addressbook.impl.validators.RegisteredNodeValidator.validateServiceEndpointsForUpdate;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.node.app.service.addressbook.ReadableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Workflow-related functionality regarding {@link HederaFunctionality#REGISTERED_NODE_UPDATE}.
 */
@Singleton
public class RegisteredNodeUpdateHandler implements TransactionHandler {
    private final AddressBookValidator addressBookValidator;

    @Inject
    public RegisteredNodeUpdateHandler(@NonNull final AddressBookValidator addressBookValidator) {
        this.addressBookValidator = requireNonNull(addressBookValidator);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().registeredNodeUpdateOrThrow();
        validateFalsePreCheck(op.registeredNodeId() < 0, INVALID_NODE_ID);
        if (op.hasAdminKey()) {
            addressBookValidator.validateAdminKey(op.adminKey());
        }
        if (op.hasDescription()) {
            validateDescription(op.description());
        }
        validateServiceEndpointsForUpdate(op.serviceEndpoint());
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().registeredNodeUpdateOrThrow();
        final var store = context.createStore(ReadableRegisteredNodeStore.class);
        final var existing = store.get(op.registeredNodeId());
        validateFalsePreCheck(existing == null, INVALID_NODE_ID);

        context.requireKeyOrThrow(existing.adminKey(), INVALID_ADMIN_KEY);
        if (op.hasAdminKey()) {
            context.requireKeyOrThrow(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);
        final var op = handleContext.body().registeredNodeUpdateOrThrow();

        final var storeFactory = handleContext.storeFactory();
        final var registeredNodeStore = storeFactory.writableStore(WritableRegisteredNodeStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);

        final var existing = registeredNodeStore.get(op.registeredNodeId());
        validateFalse(existing == null, INVALID_NODE_ID);

        final var builder = updateRegisteredNode(op, existing, accountStore);
        registeredNodeStore.put(builder.build());
    }

    private RegisteredNode.Builder updateRegisteredNode(
            @NonNull final com.hedera.hapi.node.addressbook.RegisteredNodeUpdateTransactionBody op,
            @NonNull final RegisteredNode existing,
            @NonNull final ReadableAccountStore accountStore) {
        requireNonNull(op);
        requireNonNull(existing);
        requireNonNull(accountStore);

        final var builder = existing.copyBuilder();
        if (op.hasAdminKey()) {
            builder.adminKey(op.adminKey());
        }
        if (op.hasDescription()) {
            builder.description(op.description());
        }
        if (!op.serviceEndpoint().isEmpty()) {
            builder.serviceEndpoint(op.serviceEndpoint());
        }
        return builder;
    }

    private static boolean isRemovalSentinel(@NonNull final AccountID accountId) {
        // Treat unset account oneof as 0.0.0 as well.
        return accountId.shardNum() == 0
                && accountId.realmNum() == 0
                && (!accountId.hasAccountNum() || accountId.accountNum() == 0);
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
