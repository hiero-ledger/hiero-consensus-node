// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall.OutputFn.STANDARD_OUTPUT_FN;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.encodedRc;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.utils.SchedulingUtility;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Function;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * An HTS call that simply dispatches a synthetic transaction body and returns a result that is
 * an encoded {@link com.hedera.hapi.node.base.ResponseCodeEnum}.
 */
public class DispatchForResponseCodeHtsCall extends AbstractCall {
    /**
     * The "standard" failure customizer that replaces {@link ResponseCodeEnum#INVALID_SIGNATURE} with
     * {@link ResponseCodeEnum#INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE}. (Note this code no longer
     * makes sense after the security model change that revoked use of top-level signatures; but for
     * now it is retained for backwards compatibility.)
     */
    private static final FailureCustomizer STANDARD_FAILURE_CUSTOMIZER =
            (body, code, enhancement) -> standardized(code);

    private final AccountID senderId;

    @Nullable
    private final TransactionBody syntheticBody;

    private final OutputFn outputFn;
    private final FailureCustomizer failureCustomizer;
    private final VerificationStrategy verificationStrategy;
    private final DispatchGasCalculator dispatchGasCalculator;

    /**
     * A customizer that can be used to modify the failure status of a dispatch.
     */
    @FunctionalInterface
    public interface FailureCustomizer {
        /**
         * A no-op customizer that simply returns the original failure code.
         */
        FailureCustomizer NOOP_CUSTOMIZER = (body, code, enhancement) -> code;

        /**
         * Customizes the failure status of a dispatch.
         *
         * @param syntheticBody the synthetic body that was dispatched
         * @param code the failure code
         * @param enhancement the enhancement that was used
         * @return the customized failure code
         */
        @NonNull
        ResponseCodeEnum customize(
                @NonNull TransactionBody syntheticBody,
                @NonNull ResponseCodeEnum code,
                @NonNull HederaWorldUpdater.Enhancement enhancement);
    }

    /**
     * A function that can be used to generate the output of a dispatch from its completed
     * record builder.
     */
    public interface OutputFn extends Function<ContractCallStreamBuilder, ByteBuffer> {
        /**
         * The standard output function that simply returns the encoded status.
         */
        OutputFn STANDARD_OUTPUT_FN = recordBuilder -> encodedRc(recordBuilder.status());
    }

    /**
     * Convenience overload that slightly eases construction for the most common case.
     *
     * @param attempt the attempt to translate to a dispatching
     * @param syntheticBody the synthetic body to dispatch
     * @param dispatchGasCalculator the dispatch gas calculator to use
     */
    public DispatchForResponseCodeHtsCall(
            @NonNull final HtsCallAttempt attempt,
            @Nullable final TransactionBody syntheticBody,
            @NonNull final DispatchGasCalculator dispatchGasCalculator) {
        this(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.addressIdConverter().convertSender(attempt.senderAddress()),
                syntheticBody,
                attempt.defaultVerificationStrategy(),
                dispatchGasCalculator,
                STANDARD_FAILURE_CUSTOMIZER,
                STANDARD_OUTPUT_FN);
    }

    /**
     * Convenience overload that eases construction with a failure status customizer.
     *
     * @param attempt the attempt to translate to a dispatching
     * @param syntheticBody the synthetic body to dispatch
     * @param dispatchGasCalculator the dispatch gas calculator to use
     * @param failureCustomizer the status customizer to use
     */
    public DispatchForResponseCodeHtsCall(
            @NonNull final HtsCallAttempt attempt,
            @Nullable final TransactionBody syntheticBody,
            @NonNull final DispatchGasCalculator dispatchGasCalculator,
            @NonNull final FailureCustomizer failureCustomizer) {
        this(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.addressIdConverter().convertSender(attempt.senderAddress()),
                syntheticBody,
                attempt.defaultVerificationStrategy(),
                dispatchGasCalculator,
                failureCustomizer,
                STANDARD_OUTPUT_FN);
    }

    /**
     * Convenience overload that eases construction with a custom output function.
     *
     * @param attempt the attempt to translate to a dispatching
     * @param syntheticBody the synthetic body to dispatch
     * @param dispatchGasCalculator the dispatch gas calculator to use
     * @param outputFn the output function to use
     */
    public DispatchForResponseCodeHtsCall(
            @NonNull final HtsCallAttempt attempt,
            @Nullable final TransactionBody syntheticBody,
            @NonNull final DispatchGasCalculator dispatchGasCalculator,
            @NonNull final OutputFn outputFn) {
        this(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.addressIdConverter().convertSender(attempt.senderAddress()),
                syntheticBody,
                attempt.defaultVerificationStrategy(),
                dispatchGasCalculator,
                STANDARD_FAILURE_CUSTOMIZER,
                outputFn);
    }

    /**
     * More general constructor, for cases where perhaps a custom {@link VerificationStrategy} is needed.
     *
     * @param enhancement the enhancement to use
     * @param gasCalculator the gas calculator for the system contract
     * @param senderId the id of the spender
     * @param syntheticBody the synthetic body to dispatch
     * @param verificationStrategy the verification strategy to use
     * @param dispatchGasCalculator the dispatch gas calculator to use
     * @param failureCustomizer the status customizer to use
     * @param outputFn the output function to use
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public DispatchForResponseCodeHtsCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final AccountID senderId,
            @Nullable final TransactionBody syntheticBody,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final DispatchGasCalculator dispatchGasCalculator,
            @NonNull final FailureCustomizer failureCustomizer,
            @NonNull final OutputFn outputFn) {
        super(gasCalculator, enhancement, false);
        this.senderId = Objects.requireNonNull(senderId);
        this.outputFn = Objects.requireNonNull(outputFn);
        this.syntheticBody = syntheticBody;
        this.verificationStrategy = Objects.requireNonNull(verificationStrategy);
        this.dispatchGasCalculator = Objects.requireNonNull(dispatchGasCalculator);
        this.failureCustomizer = Objects.requireNonNull(failureCustomizer);
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        if (syntheticBody == null) {
            return gasOnly(
                    haltResult(
                            ERROR_DECODING_PRECOMPILE_INPUT,
                            contractsConfigOf(frame).precompileHtsDefaultGasCost()),
                    INVALID_TRANSACTION_BODY,
                    false);
        }
        final var recordBuilder = systemContractOperations()
                .dispatch(syntheticBody, verificationStrategy, senderId, ContractCallStreamBuilder.class);
        final var gasRequirement =
                dispatchGasCalculator.gasRequirement(syntheticBody, gasCalculator, enhancement, senderId);
        var status = recordBuilder.status();
        if (status != SUCCESS) {
            status = failureCustomizer.customize(syntheticBody, status, enhancement);
            recordBuilder.status(status);
        }
        return completionWith(gasRequirement, recordBuilder, outputFn.apply(recordBuilder));
    }

    @NonNull
    @Override
    public SchedulableTransactionBody asSchedulableDispatchIn() {
        if (syntheticBody == null) {
            return super.asSchedulableDispatchIn();
        }
        return SchedulingUtility.ordinaryChildAsSchedulable(syntheticBody);
    }
}
