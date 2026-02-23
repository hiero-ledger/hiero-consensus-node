// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NODE_DELETED;
import static com.hedera.node.app.service.addressbook.AddressBookHelper.checkDABEnabled;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.WritableAccountNodeRelStore;
import com.hedera.node.app.service.addressbook.impl.WritableNodeStore;
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
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#NODE_DELETE}.
 */
@Singleton
public class NodeDeleteHandler implements TransactionHandler {

    @Inject
    public NodeDeleteHandler() {
        // exists for injection
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();

        requireNonNull(txn);
        final var op = txn.nodeDeleteOrThrow();
        final long nodeId = op.nodeId();

        validateFalsePreCheck(nodeId < 0, INVALID_NODE_ID);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        final var op = context.body().nodeDeleteOrThrow();
        final var accountConfig = context.configuration().getConfigData(AccountsConfig.class);
        final var nodeStore = context.createStore(ReadableNodeStore.class);
        final var payerNum = context.payer().accountNum();

        final var existingNode = nodeStore.get(op.nodeId());
        validateFalsePreCheck(existingNode == null, INVALID_NODE_ID);
        validateFalsePreCheck(existingNode.deleted(), NODE_DELETED);

        // if payer is not one of the system admin, treasury or address book admin, check the admin key signature
        if (payerNum != accountConfig.treasury()
                && payerNum != accountConfig.systemAdmin()
                && payerNum != accountConfig.addressBookAdmin()) {
            context.requireKeyOrThrow(existingNode.adminKey(), INVALID_ADMIN_KEY);
        }
    }

    /**
     * Given the appropriate context, deletes a node.
     *
     * @param context the {@link HandleContext} of the active transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);

        final NodeDeleteTransactionBody transactionBody = context.body().nodeDeleteOrThrow();
        var nodeId = transactionBody.nodeId();

        final var nodeStore = context.storeFactory().writableStore(WritableNodeStore.class);
        final var accountNodeRelStore = context.storeFactory().writableStore(WritableAccountNodeRelStore.class);

        Node node = nodeStore.get(nodeId);

        validateFalse(node == null, INVALID_NODE_ID);

        validateFalse(node.deleted(), NODE_DELETED);

        /* Copy all the fields from existing, and mark deleted flag  */
        final var nodeBuilder = node.copyBuilder().deleted(true);

        /* --- Put the modified node. It will be in underlying state's modifications map.
        It will not be committed to state until commit is called on the state.--- */
        nodeStore.put(nodeBuilder.build());
        accountNodeRelStore.remove(node.accountId());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        checkDABEnabled(feeContext);
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        // The price of node delete should be increased based on number of signatures.
        // The first signature is free and is accounted in the base price, so we only need to add
        // the price of the rest of the signatures.
        calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
        return calculator.calculate();
    }
}
