// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOKS_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.utils.HookValidationUtils.validateHook;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HookId;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.contract.impl.state.hooks.HookEvmFrameStateFactory;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
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
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

public class HookDispatchHandler extends AbstractContractTransactionHandler implements TransactionHandler {

    @Inject
    public HookDispatchHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractServiceComponent component) {
        super(provider, gasCalculator, component);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // no-op
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookDispatchOrThrow();
        validateTruePreCheck(op.hasCreation() || op.hasExecution() || op.hasHookIdToDelete(), INVALID_TRANSACTION_BODY);
        if (op.hasCreation()) {
            validateHook(op.creationOrThrow().details());
        } else if (op.hasExecution()) {
            validateTrue(op.executionOrThrow().hasCall(), INVALID_HOOK_CALL);
            validateTrue(op.executionOrThrow().callOrThrow().hasHookId(), INVALID_HOOK_CALL);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        final var evmHookStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var op = context.body().hookDispatchOrThrow();
        final var recordBuilder = context.savepointStack().getBaseBuilder(HookDispatchStreamBuilder.class);

        final var hookConfig = context.configuration().getConfigData(HooksConfig.class);
        validateTrue(hookConfig.hooksEnabled(), HOOKS_NOT_ENABLED);

        switch (op.action().kind()) {
            case CREATION -> {
                final var creation = op.creationOrThrow();
                final var details = creation.details();
                final var hook = evmHookStore.getEvmHook(new HookId(creation.entityId(), details.hookId()));
                validateTrue(hook == null, HOOK_ID_IN_USE);
                if (details.hasAdminKey()) {
                    context.attributeValidator().validateKey(details.adminKeyOrThrow(), INVALID_HOOK_ADMIN_KEY);
                }

                evmHookStore.createEvmHook(op.creationOrThrow());
            }
            case HOOK_ID_TO_DELETE -> {
                final var deletion = op.hookIdToDeleteOrThrow();
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
                final EvmFrameStates evmFrameStates = (ops, nativeOps, codeFactory) ->
                        new HookEvmFrameStateFactory(ops, nativeOps, codeFactory, hook);

                // Create the transaction-scoped component. Use ContractCall functionality since
                // we are just calling a contract (the hook)
                final TransactionComponent component = getTransactionComponent(context, CONTRACT_CALL, evmFrameStates);

                // Run transaction and write record as usual
                final CallOutcome outcome =
                        component.contextTransactionProcessor().call();
                final var streamBuilder = context.savepointStack().getBaseBuilder(ContractCallStreamBuilder.class);
                outcome.addCallDetailsTo(streamBuilder, context);
            }
        }
    }

    @Override
    @NonNull
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        // All charges are upfront in CryptoTransfer, so no fees here
        return Fees.FREE;
    }
}
