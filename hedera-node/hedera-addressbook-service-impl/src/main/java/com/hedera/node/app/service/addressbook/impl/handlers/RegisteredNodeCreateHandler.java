// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.RegisteredNode;
import com.hedera.node.app.service.addressbook.impl.WritableRegisteredNodeStore;
import com.hedera.node.app.service.addressbook.impl.records.RegisteredNodeCreateStreamBuilder;
import com.hedera.node.app.service.addressbook.impl.validators.AddressBookValidator;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.NodesConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Workflow-related functionality regarding {@link HederaFunctionality#REGISTERED_NODE_CREATE}.
 */
@Singleton
public class RegisteredNodeCreateHandler implements TransactionHandler {
    private final AddressBookValidator addressBookValidator;

    @Inject
    public RegisteredNodeCreateHandler(@NonNull final AddressBookValidator addressBookValidator) {
        this.addressBookValidator = requireNonNull(addressBookValidator);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().registeredNodeCreateOrThrow();
        addressBookValidator.validateAdminKey(op.adminKey());
        addressBookValidator.validateRegisteredServiceEndpointsForCreate(op.serviceEndpoint());
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().registeredNodeCreateOrThrow();
        context.requireKeyOrThrow(op.adminKeyOrThrow(), INVALID_ADMIN_KEY);
    }

    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);
        final var op = handleContext.body().registeredNodeCreateOrThrow();
        final var nodesConfig = handleContext.configuration().getConfigData(NodesConfig.class);

        addressBookValidator.validateDescription(op.description(), nodesConfig);

        final var storeFactory = handleContext.storeFactory();
        final var registeredNodeStore = storeFactory.writableStore(WritableRegisteredNodeStore.class);

        final var registeredNodeId = handleContext.nodeIdGenerator().newNodeId();
        final var node = new RegisteredNode.Builder()
                .registeredNodeId(registeredNodeId)
                .adminKey(op.adminKeyOrThrow())
                .description(op.description())
                .serviceEndpoint(op.serviceEndpoint())
                .build();
        registeredNodeStore.put(node);

        final var recordBuilder =
                handleContext.savepointStack().getBaseBuilder(RegisteredNodeCreateStreamBuilder.class);
        recordBuilder.registeredNodeID(registeredNodeId);
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
