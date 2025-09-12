// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_LAMBDA_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_EXTENSION_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_IS_NOT_A_LAMBDA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CREATION_SPEC;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
import static com.hedera.node.app.service.contract.impl.handlers.LambdaSStoreHandler.MAX_UPDATE_BYTES_LEN;
import static com.hedera.node.app.spi.workflows.DispatchOptions.setupDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoUpdateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.fee.SmartContractFeeBuilder;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent.Factory;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Provider;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Holds some state and functionality common to the smart contract transaction handlers.
 */
public abstract class AbstractContractTransactionHandler implements TransactionHandler {

    protected final Provider<Factory> provider;
    protected final ContractServiceComponent component;
    protected final GasCalculator gasCalculator;
    protected final SmartContractFeeBuilder usageEstimator = new SmartContractFeeBuilder();

    protected AbstractContractTransactionHandler(
            @NonNull final Provider<TransactionComponent.Factory> provider,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractServiceComponent component) {
        this.provider = requireNonNull(provider);
        this.gasCalculator = requireNonNull(gasCalculator);
        this.component = requireNonNull(component);
    }

    /**
     * Handle common metrics for transactions that fail `pureChecks`.
     * <p>
     * (Caller is responsible to rethrow `e`.)
     */
    protected void bumpExceptionMetrics(@NonNull final HederaFunctionality functionality, @NonNull final Exception e) {
        final var contractMetrics = component.contractMetrics();
        contractMetrics.incrementRejectedTx(functionality);
        if (e instanceof PreCheckException pce && pce.responseCode() == INSUFFICIENT_GAS) {
            contractMetrics.incrementRejectedForGasTx(functionality);
        }
    }

    @Override
    public @NonNull Fees calculateFees(@NonNull FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> getFeeMatrices(usageEstimator, fromPbj(op), sigValueObj));
    }

    /**
     * Return the fee matrix for the given transaction.  Inheritor is responsible for picking
     * the correct fee matrix for the transactions it is handling.
     * <p>
     * Used by the default implementation of `calculateFees`, above.  If inheritor overrides
     * `calculateFees` then it doesn't need to override this method.
     */
    protected /*abstract*/ @NonNull FeeData getFeeMatrices(
            @NonNull final SmartContractFeeBuilder usageEstimator,
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txBody,
            @NonNull final SigValueObj sigValObj) {
        throw new IllegalStateException("must be overridden if `calculateFees` _not_ overridden");
    }

    protected @NonNull TransactionComponent getTransactionComponent(
            @NonNull final HandleContext context, @NonNull final HederaFunctionality functionality) {
        return provider.get().create(context, functionality);
    }

    /**
     * Validates the hook creation details in a {@link CryptoCreateTransactionBody} and {@link CryptoUpdateTransactionBody}
     * @param details the hook creation details to validate
     * @throws PreCheckException if any validation fails
     */
    protected void validateHookPureChecks(final List<HookCreationDetails> details) throws PreCheckException {
        final var hookIdsSeen = new HashSet<Long>();
        for (final var hook : details) {
            validateTruePreCheck(hook.hookId() != 0L, INVALID_HOOK_ID);
            // No duplicate hook ids are allowed inside one txn
            validateTruePreCheck(hookIdsSeen.add(hook.hookId()), HOOK_ID_REPEATED_IN_CREATION_DETAILS);
            validateTruePreCheck(hook.extensionPoint() != null, HOOK_EXTENSION_EMPTY);
            validateTruePreCheck(hook.hasLambdaEvmHook(), HOOK_IS_NOT_A_LAMBDA);

            final var lambda = hook.lambdaEvmHookOrThrow();
            validateTruePreCheck(lambda.hasSpec() && lambda.specOrThrow().hasContractId(), INVALID_HOOK_CREATION_SPEC);

            for (final var storage : lambda.storageUpdates()) {
                validateTruePreCheck(
                        storage.hasStorageSlot() || storage.hasMappingEntries(), EMPTY_LAMBDA_STORAGE_UPDATE);

                if (storage.hasStorageSlot()) {
                    final var s = storage.storageSlotOrThrow();
                    // The key for a storage slot can be empty. If present, it should have minimal encoding and maximum
                    // 32 bytes
                    validateWord(s.key());
                    validateWord(s.value());
                } else if (storage.hasMappingEntries()) {
                    final var mapping = storage.mappingEntriesOrThrow();
                    for (final var e : mapping.entries()) {
                        validateTruePreCheck(e.hasKey() || e.hasPreimage(), EMPTY_LAMBDA_STORAGE_UPDATE);
                        if (e.hasKey()) {
                            validateWord(e.keyOrThrow());
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates that the given bytes are a valid "word" (i.e. a 32-byte value) for use in a lambda storage update.
     * Specifically, it checks that the length is at most 32 bytes, and that it is in its minimal representation
     * (i.e. no leading zeros).
     * @param bytes the bytes to validate
     * @throws PreCheckException if the bytes are not a valid word
     */
    private void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, HOOK_CREATION_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes == minimalBytes, HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }

    protected record HookSummary(long initialLambdaSlots, List<Long> creationHookIds) {}
}
