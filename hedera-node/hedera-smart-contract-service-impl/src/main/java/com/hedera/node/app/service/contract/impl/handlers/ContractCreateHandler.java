// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ADMIN_KEY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.throwIfUnsuccessfulCreate;
import static com.hedera.node.app.service.contract.impl.utils.HookValidationUtils.validateHookPureChecks;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.HooksConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONTRACT_CREATE}.
 */
@Singleton
public class ContractCreateHandler extends AbstractContractTransactionHandler {
    private static final AccountID REMOVE_AUTO_RENEW_ACCOUNT_SENTINEL =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(0).build();
    private final EntityIdFactory entityIdFactory;

    /**
     * Constructs a {@link ContractCreateHandler} with the given {@link Provider} and {@link GasCalculator}.
     *
     * @param provider the provider to be used
     * @param gasCalculator the gas calculator to be used
     */
    @Inject
    public ContractCreateHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractServiceComponent component,
            @NonNull final EntityIdFactory entityIdFactory) {
        super(provider, gasCalculator, component);
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        // Create the transaction-scoped component
        final var component = getTransactionComponent(context, CONTRACT_CREATE);

        final var op = context.body().contractCreateInstanceOrThrow();
        ContractID predictedId = null;
        if (!op.hookCreationDetails().isEmpty()) {
            predictedId =
                    entityIdFactory.newContractId(context.entityNumGenerator().peekAtNewEntityNum());
        }

        // Run its in-scope transaction and get the outcome
        final var outcome = component.contextTransactionProcessor().call();

        // Assemble the appropriate top-level record for the result
        final var streamBuilder = context.savepointStack().getBaseBuilder(ContractCreateStreamBuilder.class);
        outcome.addCreateDetailsTo(streamBuilder, context);

        throwIfUnsuccessfulCreate(outcome, component.hederaOperations());

        if (!op.hookCreationDetails().isEmpty()) {
            final var hookConfig = context.configuration().getConfigData(HooksConfig.class);
            validateTrue(hookConfig.hooksEnabled(), HOOKS_NOT_ENABLED);

            final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
            final var writableEvmHookStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
            final var created = accountStore.getContractById(requireNonNull(predictedId));

            createHooks(op.hookCreationDetails(), created.accountId(), writableEvmHookStore, context.attributeValidator());

            final var updated = created.copyBuilder()
                    .firstHookId(op.hookCreationDetails().getFirst().hookId())
                    .numberHooksInUse(op.hookCreationDetails().size())
                    .build();
            context.storeFactory().serviceApi(TokenServiceApi.class).updateContract(updated);
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        try {
            final var txn = context.body();
            final var op = txn.contractCreateInstanceOrThrow();

            final var intrinsicGas = gasCalculator.transactionIntrinsicGasCost(Bytes.wrap(new byte[0]), true);
            validateTruePreCheck(op.gas() >= intrinsicGas, INSUFFICIENT_GAS);
            if (!op.hookCreationDetails().isEmpty()) {
                validateHookPureChecks(op.hookCreationDetails());
            }
        } catch (@NonNull final Exception e) {
            bumpExceptionMetrics(CONTRACT_CREATE, e);
            throw e;
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().contractCreateInstanceOrThrow();

        // The transaction cannot set the admin key unless the transaction was signed by that key,
        // unless the key is a "contract ID" key, in which case we don't gather the signature (since
        // there is not one) and it is a perfectly valid arrangement.
        if (op.hasAdminKey()) {
            final var adminKey = op.adminKeyOrThrow();
            if (!adminKey.hasContractID()) {
                context.requireKey(adminKey);
            }
        }

        // If an account is to be used for auto-renewal, then the account must exist and the transaction
        // must be signed with that account's key.
        if (op.hasAutoRenewAccountId()) {
            final var autoRenewAccountID = op.autoRenewAccountIdOrThrow();
            if (!autoRenewAccountID.equals(REMOVE_AUTO_RENEW_ACCOUNT_SENTINEL)) {
                context.requireKeyOrThrow(autoRenewAccountID, INVALID_AUTORENEW_ACCOUNT);
            }
        }
    }

    @Override
    protected /*abstract*/
    @NonNull FeeData getFeeMatrices(
            @NonNull final SmartContractFeeBuilder usageEstimator,
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txBody,
            @NonNull final SigValueObj sigValObj) {
        return usageEstimator.getContractCreateTxFeeMatrices(txBody, sigValObj);
    }

    private void createHooks(
            final List<HookCreationDetails> details,
            final AccountID owner,
            final WritableEvmHookStore writableEvmHookStore, final AttributeValidator attributeValidator) {
        // empty list case or first insert into empty list
        Long nextId = null;
        for (int i = details.size() - 1; i >= 0; i--) {
            final var detail = details.get(i);
            if (detail.hasAdminKey()) {
                attributeValidator.validateKey(detail.adminKeyOrThrow(), INVALID_HOOK_ADMIN_KEY);
            }

            final var creation = HookCreation.newBuilder()
                    .entityId(HookEntityId.newBuilder().accountId(owner).build())
                    .details(detail);
            if (nextId != null) {
                creation.nextHookId(nextId);
            }
            writableEvmHookStore.createEvmHook(creation.build());
            // This one becomes "next" for the previous node in the loop
            nextId = detail.hookId();
        }
    }
}
