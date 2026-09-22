// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CallType.UNQUALIFIED_DELEGATE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hederaConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.isClprDispatch;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.contractFunctionResultFailedFor;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.successResultOf;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.txResultFailedFor;
import static com.hedera.node.app.service.contract.impl.utils.SystemContractUtils.txSuccessResultOf;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INVALID_OPERATION;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.PRECOMPILE_ERROR;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.AbstractFullContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * Abstract class for native system contracts.
 * Descendants include {@link HtsSystemContract}, {@link HasSystemContract},
 * and {@link com.hedera.node.app.service.contract.impl.exec.systemcontracts.ClprSystemContract}.
 */
@Singleton
public abstract class AbstractNativeSystemContract extends AbstractFullContract implements HederaSystemContract {
    private static final Logger log = LogManager.getLogger(AbstractNativeSystemContract.class);
    /**
     * Function selector byte length
     */
    public static final int FUNCTION_SELECTOR_LENGTH = 4;

    private static final long CLPR_VERIFIER_SYSTEM_CONTRACT_NUM = 0x16eL;
    private static final long BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM = 0x16fL;
    private static final long SEI_VERIFIER_SYSTEM_CONTRACT_NUM = 0x170L;
    private static final byte[] CLPR_VERIFIER_EVM_ADDRESS = systemContractAddress(0x6e);
    private static final byte[] BESU_QBFT_VERIFIER_EVM_ADDRESS = systemContractAddress(0x6f);
    private static final byte[] SEI_VERIFIER_EVM_ADDRESS = systemContractAddress(0x70);

    private final CallFactory callFactory;
    private final ContractMetrics contractMetrics;

    protected AbstractNativeSystemContract(
            @NonNull final String name,
            @NonNull final CallFactory callFactory,
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ContractMetrics contractMetrics) {
        super(name, gasCalculator);
        this.callFactory = requireNonNull(callFactory);
        this.contractMetrics = requireNonNull(contractMetrics);
    }

    @Override
    public FullResult computeFully(
            @NonNull final ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);
        final var callType = callTypeOf(frame);
        final var logClprVerifier = isClprVerifierDebugTarget(contractID, input);
        if (logClprVerifier) {
            log.debug(
                    "[CLPR-VERIFY-NATIVE] compute ENTER name={} contractID={} selector={} inputBytes={} "
                            + "callType={} frameStatic={} remainingGas={}",
                    getName(),
                    contractID,
                    selectorOf(input),
                    input.size(),
                    callType,
                    frame.isStatic(),
                    frame.getRemainingGas());
        }
        if (callType == UNQUALIFIED_DELEGATE) {
            if (logClprVerifier) {
                log.warn(
                        "[CLPR-VERIFY-NATIVE] compute HALT name={} contractID={} reason=UNQUALIFIED_DELEGATE",
                        getName(),
                        contractID);
            }
            return haltResult(PRECOMPILE_ERROR, frame.getRemainingGas());
        }
        final Call call;
        AbstractCallAttempt<?> attempt = null;
        try {
            // Input boundary size validation.
            // With "input <= transactionMaxBytes()" we ensure nobody can send a huge input for parsing or execution,
            // because parsing or execution can happen before any rejection, e.g. by gas or function param length.
            final var maxInputBytes = isClprDispatch(frame)
                    ? configOf(frame)
                            .getConfigData(JumboTransactionsConfig.class)
                            .maxTxnSize()
                    : hederaConfigOf(frame).transactionMaxBytes();
            validateTrue(
                    input.size() >= FUNCTION_SELECTOR_LENGTH && input.size() <= maxInputBytes,
                    INVALID_TRANSACTION_BODY);
            attempt = callFactory.createCallAttemptFrom(contractID, input, callType, frame);
            // check if the calldata size of the call to
            call = attempt.asExecutableCall();
            if (call == null) {
                return successResult(Bytes.EMPTY, 0);
            }
            if (logClprVerifier) {
                log.debug(
                        "[CLPR-VERIFY-NATIVE] translator MATCH name={} contractID={} selector={} method={} "
                                + "sender={} allowsStatic={} frameStatic={}",
                        getName(),
                        contractID,
                        selectorOf(input),
                        call.getSystemContractMethod(),
                        attempt.senderId(),
                        call.allowsStaticFrame(),
                        frame.isStatic());
            }
            if (frame.isStatic() && !call.allowsStaticFrame()) {
                // FUTURE - we should really set an explicit halt reason here; instead we just halt the frame
                // without setting a halt reason to simulate mono-service for differential testing
                if (logClprVerifier) {
                    log.warn(
                            "[CLPR-VERIFY-NATIVE] compute HALT name={} contractID={} selector={} "
                                    + "reason=STATIC_FRAME_NOT_ALLOWED method={}",
                            getName(),
                            contractID,
                            selectorOf(input),
                            call.getSystemContractMethod());
                }
                return haltResult(contractsConfigOf(frame).precompileHtsDefaultGasCost());
            }
        } catch (final HandleException exception) {
            if (logClprVerifier) {
                log.warn(
                        "[CLPR-VERIFY-NATIVE] translate HANDLE_EXCEPTION name={} contractID={} selector={} status={}",
                        getName(),
                        contractID,
                        selectorOf(input),
                        exception.getStatus());
            }
            if (exception.getStatus().equals(INVALID_TRANSACTION_BODY)) {
                return haltResult(INVALID_OPERATION, frame.getRemainingGas());
            } else {
                final var enhancement = proxyUpdaterFor(frame).enhancement();
                externalizeFailure(
                        frame.getRemainingGas(),
                        input,
                        Bytes.EMPTY,
                        requireNonNull(attempt),
                        exception.getStatus(),
                        enhancement,
                        contractID);
                return revertResult(exception.getStatus(), frame.getRemainingGas());
            }
        } catch (final Exception ignore) {
            // Input that cannot be translated to an executable call, for any
            // reason, halts the frame and consumes all remaining gas
            return haltResult(INVALID_OPERATION, frame.getRemainingGas());
        }
        return resultOfExecuting(attempt, call, input, frame, contractID);
    }

    @SuppressWarnings({"java:S2637", "java:S2259"}) // this function is going to be refactored soon.
    private FullResult resultOfExecuting(
            @NonNull final AbstractCallAttempt<?> attempt,
            @NonNull final Call call,
            @NonNull final Bytes input,
            @NonNull final MessageFrame frame,
            @NonNull final ContractID contractID) {
        final Call.PricedResult pricedResult;
        final var logClprVerifier = isClprVerifierDebugTarget(contractID, input);
        try {
            if (logClprVerifier) {
                log.debug(
                        "[CLPR-VERIFY-NATIVE] execute START name={} contractID={} selector={} method={} "
                                + "sender={} remainingGas={}",
                        getName(),
                        contractID,
                        selectorOf(input),
                        call.getSystemContractMethod(),
                        attempt.senderId(),
                        frame.getRemainingGas());
            }
            pricedResult = call.execute(frame);
            final var gasRequirement = pricedResult.fullResult().gasRequirement();
            final var insufficientGas = frame.getRemainingGas() < gasRequirement;
            final var dispatchedRecordBuilder = pricedResult.fullResult().recordBuilder();
            if (logClprVerifier) {
                log.debug(
                        "[CLPR-VERIFY-NATIVE] execute RESULT name={} contractID={} selector={} method={} "
                                + "responseCode={} evmState={} gasRequirement={} remainingGas={} insufficientGas={} "
                                + "recordBuilderPresent={} isViewCall={} outputBytes={}",
                        getName(),
                        contractID,
                        selectorOf(input),
                        call.getSystemContractMethod(),
                        pricedResult.responseCode(),
                        pricedResult.fullResult().result().state(),
                        gasRequirement,
                        frame.getRemainingGas(),
                        insufficientGas,
                        dispatchedRecordBuilder != null,
                        pricedResult.isViewCall(),
                        pricedResult.fullResult().output().size());
            }
            if (dispatchedRecordBuilder != null) {
                if (logClprVerifier) {
                    log.debug(
                            "[CLPR-VERIFY-NATIVE] externalize via recordBuilder name={} contractID={} selector={} "
                                    + "insufficientGas={}",
                            getName(),
                            contractID,
                            selectorOf(input),
                            insufficientGas);
                }
                // (FUTURE) Remove after switching to block stream — BlockStreamBuilder doesn't support
                // contractCallResult.
                final var streamMode =
                        configOf(frame).getConfigData(BlockStreamConfig.class).streamMode();
                if (insufficientGas) {
                    dispatchedRecordBuilder.status(INSUFFICIENT_GAS);
                    final var callData = tuweniToPbjBytes(input);
                    if (streamMode != BLOCKS) {
                        dispatchedRecordBuilder.contractCallResult(pricedResult.asResultOfInsufficientGasRemaining(
                                attempt.senderId(), contractID, callData, frame.getRemainingGas()));
                    }
                    dispatchedRecordBuilder.evmCallTransactionResult(pricedResult.txAsResultOfInsufficientGasRemaining(
                            attempt.senderId(), contractID, callData, frame.getRemainingGas()));
                } else {
                    if (streamMode != BLOCKS) {
                        dispatchedRecordBuilder.contractCallResult(pricedResult.asResultOfCall(
                                attempt.senderId(), contractID, tuweniToPbjBytes(input), frame.getRemainingGas()));
                    }
                    dispatchedRecordBuilder.evmCallTransactionResult(pricedResult.txAsResultOfCall(
                            attempt.senderId(), contractID, tuweniToPbjBytes(input), frame.getRemainingGas()));
                }
            } else if (pricedResult.isViewCall()) {
                final var proxyWorldUpdater = proxyUpdaterFor(frame);
                final var enhancement = proxyWorldUpdater.enhancement();
                // Insufficient gas preempts any other response code
                final var status = insufficientGas ? INSUFFICIENT_GAS : pricedResult.responseCode();
                if (logClprVerifier) {
                    log.debug(
                            "[CLPR-VERIFY-NATIVE] externalize view result name={} contractID={} selector={} status={} "
                                    + "responseCode={} outputBytes={}",
                            getName(),
                            contractID,
                            selectorOf(input),
                            status,
                            pricedResult.responseCode(),
                            insufficientGas
                                    ? 0
                                    : pricedResult.fullResult().output().size());
                }
                if (status == SUCCESS) {
                    enhancement
                            .systemOperations()
                            .externalizeResult(
                                    successResultOf(
                                            attempt.senderId(),
                                            pricedResult.fullResult(),
                                            frame,
                                            !call.allowsStaticFrame()),
                                    pricedResult.responseCode(),
                                    enhancement
                                            .systemOperations()
                                            .syntheticSignedTxForNativeCall(input, contractID, true),
                                    txSuccessResultOf(
                                            attempt.senderId(),
                                            pricedResult.fullResult(),
                                            frame,
                                            !call.allowsStaticFrame()));
                } else {
                    externalizeFailure(
                            gasRequirement,
                            input,
                            insufficientGas
                                    ? Bytes.EMPTY
                                    : pricedResult.fullResult().output(),
                            attempt,
                            status,
                            enhancement,
                            contractID);
                }
            } else if (logClprVerifier) {
                log.warn(
                        "[CLPR-VERIFY-NATIVE] no externalization path name={} contractID={} selector={} "
                                + "responseCode={} evmState={} recordBuilderPresent=false isViewCall=false",
                        getName(),
                        contractID,
                        selectorOf(input),
                        pricedResult.responseCode(),
                        pricedResult.fullResult().result().state());
            }
        } catch (final HandleException handleException) {
            if (handleException.getStatus().equals(INVALID_TRANSACTION_BODY)) {
                log.warn(
                        "{} INVALID_TRANSACTION_BODY: reason=EXECUTE_HANDLE_EXCEPTION contractID={} selector={} "
                                + "method={} inputBytes={} callType={} frameStatic={} remainingGas={} status={}",
                        getName(),
                        contractID,
                        selectorOf(input),
                        call.getSystemContractMethod(),
                        input.size(),
                        callTypeOf(frame),
                        frame.isStatic(),
                        frame.getRemainingGas(),
                        handleException.getStatus(),
                        handleException);
            }
            if (logClprVerifier) {
                log.warn(
                        "[CLPR-VERIFY-NATIVE] execute HANDLE_EXCEPTION name={} contractID={} selector={} status={}",
                        getName(),
                        contractID,
                        selectorOf(input),
                        handleException.getStatus());
            }
            final var fullResult = haltHandleException(handleException, frame.getRemainingGas());
            reportToMetrics(call, fullResult);
            return fullResult;
        } catch (final Exception internal) {
            log.error("Unhandled failure for input {} to native system contract", input, internal);
            final var fullResult = haltResult(PRECOMPILE_ERROR, frame.getRemainingGas());
            reportToMetrics(call, fullResult);
            return fullResult;
        }
        final var fullResult = pricedResult.fullResult();
        reportToMetrics(call, fullResult);
        return fullResult;
    }

    private void reportToMetrics(@NonNull final Call call, @NonNull final FullResult fullResult) {
        contractMetrics.incrementSystemMethodCall(
                call.getSystemContractMethod(), fullResult.result().state());
    }

    private static void externalizeFailure(
            final long gasRequirement,
            @NonNull final Bytes input,
            @NonNull final Bytes output,
            @NonNull final AbstractCallAttempt<?> attempt,
            @NonNull final ResponseCodeEnum status,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final ContractID contractID) {
        enhancement
                .systemOperations()
                .externalizeResult(
                        contractFunctionResultFailedFor(
                                attempt.senderId(), output, gasRequirement, status.toString(), contractID),
                        status,
                        enhancement.systemOperations().syntheticSignedTxForNativeCall(input, contractID, true),
                        txResultFailedFor(attempt.senderId(), output, gasRequirement, status.toString(), contractID));
    }

    // potentially other cases could be handled here if necessary; a re-thrown exception is
    // resolved by FrameRunner as an exceptional halt of the whole EVM transaction that
    // preserves the exception's status
    private static FullResult haltHandleException(
            @NonNull final HandleException handleException, final long remainingGas) {
        if (handleException.getStatus().equals(MAX_CHILD_RECORDS_EXCEEDED)) {
            return haltResult(CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS, remainingGas);
        }
        throw handleException;
    }

    //
    protected abstract FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame);

    private static boolean isClprVerifierDebugTarget(@NonNull final ContractID contractID, @NonNull final Bytes input) {
        if (contractID.hasContractNum()) {
            final var num = contractID.contractNumOrThrow();
            return num == CLPR_VERIFIER_SYSTEM_CONTRACT_NUM
                    || num == BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NUM
                    || num == SEI_VERIFIER_SYSTEM_CONTRACT_NUM;
        }
        if (contractID.hasEvmAddress()) {
            final var evmAddress = contractID.evmAddressOrThrow().toByteArray();
            return Arrays.equals(evmAddress, CLPR_VERIFIER_EVM_ADDRESS)
                    || Arrays.equals(evmAddress, BESU_QBFT_VERIFIER_EVM_ADDRESS)
                    || Arrays.equals(evmAddress, SEI_VERIFIER_EVM_ADDRESS);
        }
        return false;
    }

    @NonNull
    private static Bytes selectorOf(@NonNull final Bytes input) {
        return input.slice(0, Math.min(FUNCTION_SELECTOR_LENGTH, input.size()));
    }

    private static byte[] systemContractAddress(final int lowByte) {
        final var address = new byte[20];
        address[18] = 0x01;
        address[19] = (byte) lowByte;
        return address;
    }
}
