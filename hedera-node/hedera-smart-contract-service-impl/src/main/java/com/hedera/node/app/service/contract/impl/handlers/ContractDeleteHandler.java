// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OBTAINER_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OBTAINER_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OBTAINER_SAME_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumericContractId;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.addressbook.ReadableAccountNodeRelStore;
import com.hedera.node.app.service.contract.impl.records.ContractDeleteStreamBuilder;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_DELETE}.
 */
@Singleton
public class ContractDeleteHandler implements TransactionHandler {
    private final SmartContractFeeBuilder usageEstimator = new SmartContractFeeBuilder();
    private final EntityIdFactory entityIdFactory;
    /**
     * Default constructor for injection.
     */
    @Inject
    public ContractDeleteHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = entityIdFactory;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.contractDeleteInstanceOrThrow();
        validateFalsePreCheck(op.permanentRemoval(), PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION);

        // The contract ID must be present on the transaction
        final var contractID = op.contractID();
        mustExist(contractID, INVALID_CONTRACT_ID);

        validateTruePreCheck(op.hasTransferAccountID() || op.hasTransferContractID(), OBTAINER_REQUIRED);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().contractDeleteInstanceOrThrow();
        // A contract corresponding to that contract ID must exist in state (otherwise we have nothing to delete)
        final var contract = context.createStore(ReadableAccountStore.class).getContractById(op.contractIDOrThrow());
        mustExist(contract, INVALID_CONTRACT_ID);
        // If there is not an admin key, then the contract is immutable. Otherwise, the transaction must
        // be signed by the admin key.
        context.requireKeyOrThrow(contract.key(), MODIFYING_IMMUTABLE_CONTRACT);
        final var adminKey = contract.keyOrThrow();
        validateFalsePreCheck(
                adminKey.hasContractID() || adminKey.hasDelegatableContractId(), MODIFYING_IMMUTABLE_CONTRACT);
        // If there is a transfer account ID, and IF that account has receiverSigRequired set, then the transaction
        // must be signed by that account's key. Same if instead it uses a contract as the transfer target.
        if (op.hasTransferAccountID()) {
            context.requireKeyIfReceiverSigRequired(op.transferAccountID(), INVALID_TRANSFER_ACCOUNT_ID);
        } else if (op.hasTransferContractID()) {
            context.requireKeyIfReceiverSigRequired(op.transferContractID(), INVALID_CONTRACT_ID);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().contractDeleteInstanceOrThrow();
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        final var toBeDeleted = requireNonNull(accountStore.getContractById(op.contractIDOrThrow()));
        validateFalse(toBeDeleted.deleted(), CONTRACT_DELETED);
        final var obtainer = getObtainer(accountStore, op);
        validateTrue(obtainer != null, OBTAINER_DOES_NOT_EXIST);
        if (obtainer.deleted()) {
            throw new HandleException(obtainer.smartContract() ? INVALID_CONTRACT_ID : OBTAINER_DOES_NOT_EXIST);
        }
        validateFalse(toBeDeleted.accountIdOrThrow().equals(obtainer.accountIdOrThrow()), OBTAINER_SAME_CONTRACT_ID);
        final var recordBuilder = context.savepointStack().getBaseBuilder(ContractDeleteStreamBuilder.class);
        final var deletedId = toBeDeleted.accountIdOrThrow();
        final var accountNodeRelStore = context.storeFactory().readableStore(ReadableAccountNodeRelStore.class);
        context.storeFactory()
                .serviceApi(TokenServiceApi.class)
                .deleteAndTransfer(
                        deletedId,
                        obtainer.accountIdOrThrow(),
                        context.expiryValidator(),
                        recordBuilder,
                        accountNodeRelStore);
        recordBuilder.contractID(asNumericContractId(entityIdFactory, deletedId));
    }

    private @Nullable Account getObtainer(
            @NonNull final ReadableAccountStore accountStore, @NonNull final ContractDeleteTransactionBody op) {
        return op.hasTransferAccountID()
                ? accountStore.getAccountById(op.transferAccountIDOrThrow())
                : accountStore.getContractById(op.transferContractIDOrThrow());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(
                        sigValueObj -> usageEstimator.getContractDeleteTxFeeMatrices(fromPbj(op), sigValueObj));
    }
}
