// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.ERROR_DECODING_PRECOMPILE_INPUT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * An HSS call that simply dispatches a synthetic transaction body and returns a result that is an encoded
 * {@link ResponseCodeEnum}.
 */
public class DispatchForResponseCodeHssCall extends AbstractCall {

    private final AccountID senderId;

    @Nullable
    private final TransactionBody syntheticBody;

    private final VerificationStrategy verificationStrategy;
    private final DispatchGasCalculator dispatchGasCalculator;
    private final Set<Key> authorizingKeys;
    private final DispatchOptions.UsePresetTxnId usePresetTxnId;
    private final Function<ContractCallStreamBuilder, ByteBuffer> resultEncoder;

    /**
     * Convenience overload that slightly eases construction for the most common case.
     *
     * @param attempt               the attempt to translate to a dispatching
     * @param syntheticBody         the synthetic body to dispatch
     * @param dispatchGasCalculator the dispatch gas calculator to use
     */
    public DispatchForResponseCodeHssCall(
            @NonNull final HssCallAttempt attempt,
            @Nullable final TransactionBody syntheticBody,
            @NonNull final DispatchGasCalculator dispatchGasCalculator,
            @NonNull final Set<Key> authorizingKeys) {
        this(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                attempt.addressIdConverter().convertSender(attempt.senderAddress()),
                syntheticBody,
                attempt.defaultVerificationStrategy(),
                dispatchGasCalculator,
                authorizingKeys,
                e -> ReturnTypes.encodedRc(e.status()));
    }

    /**
     * More general constructor, for cases where perhaps a custom {@link VerificationStrategy} is needed.
     *
     * @param enhancement           the enhancement to use
     * @param gasCalculator         the gas calculator to use
     * @param senderId              the id of the spender
     * @param syntheticBody         the synthetic body to dispatch
     * @param verificationStrategy  the verification strategy to use
     * @param dispatchGasCalculator the dispatch gas calculator to use
     * @param authorizingKeys       the keys authorizing the dispatch
     * @param resultEncoder         the encoder to encode ouptup of the result
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public DispatchForResponseCodeHssCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final AccountID senderId,
            @Nullable final TransactionBody syntheticBody,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final DispatchGasCalculator dispatchGasCalculator,
            @NonNull final Set<Key> authorizingKeys,
            @NonNull final Function<ContractCallStreamBuilder, ByteBuffer> resultEncoder) {
        super(gasCalculator, enhancement, false);
        this.senderId = Objects.requireNonNull(senderId);
        this.syntheticBody = syntheticBody;
        this.verificationStrategy = Objects.requireNonNull(verificationStrategy);
        this.dispatchGasCalculator = Objects.requireNonNull(dispatchGasCalculator);
        this.authorizingKeys = authorizingKeys;
        this.usePresetTxnId =
                syntheticBody != null && syntheticBody.hasTransactionID() ? UsePresetTxnId.YES : UsePresetTxnId.NO;
        this.resultEncoder = resultEncoder;
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
                .dispatch(
                        syntheticBody,
                        verificationStrategy,
                        senderId,
                        ContractCallStreamBuilder.class,
                        authorizingKeys,
                        usePresetTxnId);
        final var gasRequirement =
                dispatchGasCalculator.gasRequirement(syntheticBody, gasCalculator, enhancement, senderId);
        return completionWith(gasRequirement, recordBuilder, resultEncoder.apply(recordBuilder));
    }

    /**
     * Encodes the given {@code ContractCallStreamBuilder.status} and {@code ContractCallStreamBuilder.scheduleID} as a
     * return value for a schedule create call.
     *
     * @param recordBuilder the result of the dispatch to encode
     * @return the encoded status
     */
    public static ByteBuffer scheduleCreateResultEncode(@NonNull final ContractCallStreamBuilder recordBuilder) {
        return ReturnTypes.RC_AND_ADDRESS_ENCODER.encode(Tuple.of(
                (long) recordBuilder.status().protoOrdinal(),
                recordBuilder.scheduleID() != null
                        ? headlongAddressOf(recordBuilder.scheduleID())
                        : ReturnTypes.ZERO_ADDRESS));
    }
}
