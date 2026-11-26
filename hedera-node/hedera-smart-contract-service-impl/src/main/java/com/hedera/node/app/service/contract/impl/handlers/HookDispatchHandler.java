// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CREATION_SPEC;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.asAccountId;
import static com.hedera.node.app.service.contract.impl.utils.HookValidationUtils.validateHook;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.contract.impl.state.hooks.HookEvmFrameStateFactory;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HooksConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Provider;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

public class HookDispatchHandler extends AbstractContractTransactionHandler implements TransactionHandler {

    @Inject
    public HookDispatchHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final HederaGasCalculator gasCalculator,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ContractServiceComponent component) {
        super(provider, gasCalculator, entityIdFactory, component);
    }

    /**
     * Gathers the target entity's key for CREATION and DELETION so an attacker cannot attach or
     * remove hooks on another account/contract merely by being permitted to submit {@code HOOK_DISPATCH}.
     * EXECUTION intentionally gathers no keys — {@code TransferExecutor} waives the debit key via
     * {@code usesHook} (HIP-1195), and a missing hook fails closed with {@code HOOK_NOT_FOUND}.
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookDispatchOrThrow();
        if (op.hasCreation()) {
            context.requireKeyOrThrow(
                    ownerAccountIdForPreHandle(op.creationOrThrow().entityIdOrThrow()), INVALID_ACCOUNT_ID);
        } else if (op.hasHookIdToDelete()) {
            context.requireKeyOrThrow(
                    ownerAccountIdForPreHandle(op.hookIdToDeleteOrThrow().entityIdOrThrow()), INVALID_ACCOUNT_ID);
        }
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookDispatchOrThrow();
        validateTruePreCheck(op.hasCreation() || op.hasExecution() || op.hasHookIdToDelete(), INVALID_TRANSACTION_BODY);
        if (op.hasCreation()) {
            validateTruePreCheck(op.creationOrThrow().hasDetails(), INVALID_HOOK_CREATION_SPEC);
            validateTruePreCheck(op.creationOrThrow().hasEntityId(), INVALID_HOOK_CREATION_SPEC);
            validateHook(op.creationOrThrow().detailsOrThrow());
        } else if (op.hasExecution()) {
            validateTrue(op.executionOrThrow().hasCall(), INVALID_HOOK_CALL);
            validateTrue(op.executionOrThrow().callOrThrow().hasHookId(), INVALID_HOOK_CALL);
            validateTrue(
                    op.executionOrThrow().callOrThrow().evmHookCallOrThrow().gasLimit() > 0, INSUFFICIENT_GAS);
        } else if (op.hasHookIdToDelete()) {
            validateTruePreCheck(op.hookIdToDeleteOrThrow().hasEntityId(), INVALID_ACCOUNT_ID);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var evmHookStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var op = context.body().hookDispatchOrThrow();
        final var recordBuilder = context.savepointStack().getBaseBuilder(HookDispatchStreamBuilder.class);

        final var hooksConfig = context.configuration().getConfigData(HooksConfig.class);
        validateTrue(hooksConfig.hooksEnabled(), HOOKS_NOT_ENABLED);

        switch (op.action().kind()) {
            case CREATION -> {
                final var creation = op.creationOrThrow();
                assertOwnerAuthorized(context, creation.entityIdOrThrow());
                final var details = creation.detailsOrThrow();
                final var hook = evmHookStore.getEvmHook(new HookId(creation.entityId(), details.hookId()));
                validateTrue(hook == null, HOOK_ID_IN_USE);
                if (details.hasAdminKey()) {
                    context.attributeValidator().validateKey(details.adminKeyOrThrow(), INVALID_HOOK_ADMIN_KEY);
                }
                final var updatedSlots =
                        evmHookStore.createEvmHook(op.creationOrThrow(), hooksConfig.maxNumberOfHooks());
                recordBuilder.setDeltaStorageSlotsUpdated(updatedSlots);
            }
            case HOOK_ID_TO_DELETE -> {
                final var deletion = op.hookIdToDeleteOrThrow();
                assertOwnerAuthorized(context, deletion.entityIdOrThrow());
                final var hook = evmHookStore.getEvmHook(new HookId(deletion.entityId(), deletion.hookId()));
                validateTrue(hook != null, HOOK_NOT_FOUND);
                evmHookStore.remove(op.hookIdToDeleteOrThrow());
                // Set the next available hook ID of the deleted hook to the record builder. This will be used by
                // the caller to set the next available hook ID in the account if the deleted hook is the head
                if (hook.nextHookId() != null) {
                    recordBuilder.nextHookId(hook.nextHookId());
                }
            }
            case EXECUTION -> {
                final var execution = op.executionOrThrow();
                final var call = execution.callOrThrow();
                final var hookKey = new HookId(execution.hookEntityIdOrThrow(), call.hookIdOrThrow());
                final var hook = evmHookStore.getEvmHook(hookKey);
                validateTrue(hook != null, HOOK_NOT_FOUND);

                // Build the strategy that will produce a HookEvmFrameStateFactory for this transaction
                final EvmFrameStates evmFrameStates =
                        (ops, nativeOps, codeCache) -> new HookEvmFrameStateFactory(ops, nativeOps, hook, codeCache);
                // Create the transaction-scoped component. Use ContractCall functionality since
                // we are just calling a contract (the hook)
                final var component = getTransactionComponent(context, CONTRACT_CALL, evmFrameStates);
                // Run transaction and write record as usual
                final var outcome = component.contextTransactionProcessor().call();
                final var streamBuilder = context.savepointStack().getBaseBuilder(ContractCallStreamBuilder.class);
                outcome.addCallDetailsTo(streamBuilder, context, entityIdFactory);

                validateTrue(outcome.status() == SUCCESS, outcome.status());
            }
        }
    }

    /**
     * Defense-in-depth ownership check for CREATION/DELETION: the target entity must exist and its
     * key must have authorized this dispatch. Child step dispatches from CryptoCreate/Update (and
     * ContractCreate/Update) use a preauthorized key verifier, so the parent's already-verified
     * owner signature continues to satisfy this check. A top-level attacker without the victim's
     * signature fails with {@link com.hedera.hapi.node.base.ResponseCodeEnum#INVALID_SIGNATURE}.
     */
    private static void assertOwnerAuthorized(
            @NonNull final HandleContext context, @NonNull final HookEntityId entityId) {
        final var ownerId = ownerAccountIdOf(entityId);
        final var account = context.storeFactory()
                .readableStore(ReadableAccountStore.class)
                .getAccountById(ownerId);
        validateTrue(account != null && account.hasKey(), INVALID_ACCOUNT_ID);
        final var verification = context.keyVerifier().verificationFor(account.keyOrThrow());
        validateTrue(verification.passed(), INVALID_SIGNATURE);
    }

    private static AccountID ownerAccountIdForPreHandle(@NonNull final HookEntityId entityId)
            throws PreCheckException {
        if (entityId.hasAccountId()) {
            return entityId.accountIdOrThrow();
        }
        if (entityId.hasContractId()) {
            return asAccountId(entityId.contractIdOrThrow());
        }
        throw new PreCheckException(INVALID_ACCOUNT_ID);
    }

    private static AccountID ownerAccountIdOf(@NonNull final HookEntityId entityId) {
        if (entityId.hasAccountId()) {
            return entityId.accountIdOrThrow();
        }
        if (entityId.hasContractId()) {
            return asAccountId(entityId.contractIdOrThrow());
        }
        throw new HandleException(INVALID_ACCOUNT_ID);
    }

    public static class FeeCalculator implements ServiceFeeCalculator {
        @Override
        public TransactionBody.DataOneOfType getTransactionType() {
            return TransactionBody.DataOneOfType.HOOK_DISPATCH;
        }

        @Override
        public void accumulateServiceFee(
                @NonNull final TransactionBody txnBody,
                @NonNull final SimpleFeeContext simpleFeeContext,
                @NonNull final FeeResult feeResult,
                @NonNull final FeeSchedule feeSchedule) {
            // HOOK dispatch should be free, the fee is charged as part of the CryptoTransfer
            feeResult.clearFees();
        }
    }
}
