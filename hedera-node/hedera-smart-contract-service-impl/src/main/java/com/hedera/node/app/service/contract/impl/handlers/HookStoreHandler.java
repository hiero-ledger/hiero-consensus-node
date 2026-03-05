// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.UNSET;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_EVM_HOOK_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EVM_HOOK_STORAGE_UPDATE_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_IS_NOT_AN_EVM_HOOK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOO_MANY_EVM_HOOK_STORAGE_UPDATES;
import static com.hedera.hapi.node.state.hooks.HookType.EVM_HOOK;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.asAccountId;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.hooks.EvmHookStorageSlot;
import com.hedera.hapi.node.hooks.EvmHookStorageUpdate;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.ReadableEvmHookStore;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HooksConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;

@Singleton
public class HookStoreHandler implements TransactionHandler {
    /**
     * Each storage update is a fixed $0.005 fee.
     */
    private static final long TINYCENTS_PER_UPDATE = 50_000_000L;

    public static final long MAX_UPDATE_BYTES_LEN = 32L;

    public static class FeeCalculator implements ServiceFeeCalculator {
        @Override
        public TransactionBody.DataOneOfType getTransactionType() {
            return TransactionBody.DataOneOfType.HOOK_STORE;
        }

        @Override
        public void accumulateServiceFee(
                @NonNull final TransactionBody txnBody,
                @NonNull SimpleFeeContext simpleFeeContext,
                @NonNull final FeeResult feeResult,
                @NonNull final FeeSchedule feeSchedule) {
            requireNonNull(txnBody);
            requireNonNull(feeResult);
            requireNonNull(feeSchedule);
            final var fee = lookupServiceFee(feeSchedule, HederaFunctionality.HOOK_STORE);
            requireNonNull(fee);
            feeResult.setServiceBaseFeeTinycents(fee.baseFee());
            final var op = txnBody.hookStoreOrThrow();
            addExtraFee(feeResult, fee, Extra.HOOK_SLOT_UPDATE, feeSchedule, slotCount(op.storageUpdates()));
        }
    }

    @Inject
    public HookStoreHandler() {
        // Dagger2
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookStoreOrThrow();
        validateTruePreCheck(op.hasHookId(), INVALID_HOOK_ID);
        final var hookId = op.hookIdOrThrow();
        validateTruePreCheck(hookId.hasEntityId(), INVALID_HOOK_ID);
        final var ownerType = hookId.entityIdOrThrow().entityId().kind();
        validateTruePreCheck(ownerType != UNSET, INVALID_HOOK_ID);
        validateFalsePreCheck(op.storageUpdates().isEmpty(), EMPTY_EVM_HOOK_STORAGE_UPDATE);
        for (final var update : op.storageUpdates()) {
            if (update.hasStorageSlot()) {
                validateSlot(update.storageSlotOrThrow());
            } else if (update.hasMappingEntries()) {
                final var mappingEntries = update.mappingEntriesOrThrow();
                validateWord(mappingEntries.mappingSlot());
                for (final var entry : mappingEntries.entries()) {
                    validateEntry(entry);
                }
            } else {
                throw new PreCheckException(EMPTY_EVM_HOOK_STORAGE_UPDATE);
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().hookStoreOrThrow();
        final var store = context.createStore(ReadableEvmHookStore.class);
        // We translate any contract id used at the HAPI boundary for internal simplicity
        final var hookId = effectiveHookId(op.hookIdOrThrow());
        final var hook = store.getEvmHook(hookId);
        // Since we only create hooks using numeric ids, this implicitly asserts hookEntityId uses a numeric id
        validateTruePreCheck(hook != null, HOOK_NOT_FOUND);
        validateTruePreCheck(hook.type() == EVM_HOOK, HOOK_IS_NOT_AN_EVM_HOOK);
        // (FUTURE) As non-account entities acquire hooks, switch on more cases here
        final var ownerAccountId = hookId.entityIdOrThrow().accountIdOrThrow();
        if (hook.hasAdminKey()) {
            // Storage for an EVM with an admin key can be managed by either the creator or the admin
            context.requireKeyOrThrow(
                    ownerAccountId,
                    ownerKey -> Key.newBuilder()
                            .thresholdKey(ThresholdKey.newBuilder()
                                    .threshold(1)
                                    .keys(new KeyList(List.of(ownerKey, hook.adminKeyOrThrow()))))
                            .build(),
                    INVALID_HOOK_ID);
        } else {
            context.requireKeyOrThrowOnDeleted(ownerAccountId, INVALID_HOOK_ID);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().hookStoreOrThrow();
        final var evmHookStore = context.storeFactory().writableStore(WritableEvmHookStore.class);
        final var storageUpdates = op.storageUpdates();
        final var config = context.configuration().getConfigData(HooksConfig.class);
        validateTrue(storageUpdates.size() <= config.maxHookStoreUpdates(), TOO_MANY_EVM_HOOK_STORAGE_UPDATES);
        // We translate any contract id used at the HAPI boundary for internal simplicity
        final var hookId = effectiveHookId(op.hookIdOrThrow());
        final int delta = evmHookStore.updateStorage(hookId, op.storageUpdates());
        validateTrue(
                evmHookStore.numStorageSlotsInState() <= config.maxEvmHookStorageSlots(),
                MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.updateHookStorageSlots(
                hookId.entityIdOrThrow().accountIdOrThrow(),
                delta,
                // But if the user expected a contract, enforce that here
                op.hookIdOrThrow().entityIdOrThrow().hasContractId());
    }

    @Override
    public @NonNull Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        final var op = feeContext.body().hookStoreOrThrow();
        final int n = slotCount(op.storageUpdates());
        // Simple trick within legacy fee context to ensure ~$0.005 per update, regardless of gas price
        final long p = feeContext.getGasPriceInTinycents();
        return calculator.addGas((n * TINYCENTS_PER_UPDATE + (p - 1)) / p).calculate();
    }

    private static int slotCount(@NonNull final List<EvmHookStorageUpdate> storageUpdates) {
        int count = 0;
        for (final var update : storageUpdates) {
            if (update.hasStorageSlot()) {
                count++;
            } else if (update.hasMappingEntries()) {
                count += update.mappingEntriesOrThrow().entries().size();
            }
        }
        return count;
    }

    private void validateSlot(@NonNull final EvmHookStorageSlot slot) throws PreCheckException {
        validateWord(slot.key());
        validateWord(slot.value());
    }

    private void validateEntry(@NonNull final EvmHookMappingEntry entry) throws PreCheckException {
        validateWord(entry.value());
    }

    private void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, EVM_HOOK_STORAGE_UPDATE_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes == minimalBytes, EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }

    /**
     * Returns the effective hook id for the given hook id.  If the given hook id uses a contract id,
     * returns a hook id that uses the account id of the contract. Otherwise, returns the given hook id.
     * @param hookId the hook id
     * @return the effective hook id
     */
    private static HookId effectiveHookId(@NonNull final HookId hookId) {
        final var entityId = hookId.entityIdOrThrow();
        return entityId.hasContractId()
                ? HookId.newBuilder()
                        .entityId(HookEntityId.newBuilder().accountId(asAccountId(entityId.contractIdOrThrow())))
                        .hookId(hookId.hookId())
                        .build()
                : hookId;
    }
}
