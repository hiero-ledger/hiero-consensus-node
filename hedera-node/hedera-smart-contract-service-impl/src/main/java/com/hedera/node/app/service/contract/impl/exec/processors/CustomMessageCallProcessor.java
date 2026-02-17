// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import static com.hedera.hapi.streams.ContractActionType.PRECOMPILE;
import static com.hedera.hapi.streams.ContractActionType.SYSTEM;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.*;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateCommons.createMethodsSet;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.*;
import static com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure.MISSING_RECEIVER_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure.RESULT_CANNOT_BE_EXTERNALIZED;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.hyperledger.besu.evm.frame.MessageFrame.State.EXCEPTIONAL_HALT;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmContract;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ScheduleEvmAccount;
import com.hedera.node.app.service.contract.impl.state.TokenEvmAccount;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;
import org.hyperledger.besu.evm.precompile.PrecompiledContract.PrecompileContractResult;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.CodeDelegationHelper;

/**
 * A {@link MessageCallProcessor} customized to,
 * <ol>
 *  <li>Call Hedera-specific System Contracts and precompiles.</li>
 *  <li>Impose Hedera restrictions in the system account range.</li>
 *  <li>Do lazy creation when appropriate.</li>
 * </ol>
 * Note these only require changing {@link MessageCallProcessor#start(MessageFrame, OperationTracer)},
 * and the core {@link MessageCallProcessor#process(MessageFrame, OperationTracer)} logic we inherit.
 */
public class CustomMessageCallProcessor extends MessageCallProcessor {
    private static final Logger LOG = LogManager.getLogger(CustomMessageCallProcessor.class);

    private record CustomMessageCallContext(
            @NonNull MessageFrame frame,
            @NonNull OperationTracer tracer,
            @NonNull Optional<Account> contractAccount,
            // The address whose code is to be executed: either frame.getContractAddress(),
            // EIP-7702 delegation target or HAS address (for account proxy redirect)
            @NonNull Address executableCodeAddress,
            boolean isCodeDelegation,
            boolean transfersValue) {

        static CustomMessageCallContext create(MessageFrame frame, OperationTracer tracer) {
            LOG.warn("XXX CustomMessageCallContext.create frame.getContractAddress = " + frame.getContractAddress());
            final Account contractAccount = frame.getWorldUpdater().get(frame.getContractAddress());

            final Address executableCodeAddress;
            final boolean isCodeDelegation;
            if (contractAccount != null) {
                LOG.warn("XXX contractAccount is not null");

                final var hasCodeDelegation = CodeDelegationHelper.hasCodeDelegation(contractAccount.getCode());
                final var isEoa = contractAccount.getCode().isEmpty() || hasCodeDelegation;
                final var isEligibleForHasRedirect =
                        HasSystemContract.isPayloadEligibleForHasProxyRedirect(frame.getInputData());
                LOG.warn(
                        "XXX contractAccount is not null hasCodeDelegation {}, isEoa {}, isEligibleForHasRedirect {}",
                        hasCodeDelegation,
                        isEoa,
                        isEligibleForHasRedirect);

                if (isEoa && isEligibleForHasRedirect) {
                    LOG.warn("XXX HAS redirect");

                    // HAS proxy calls have priority, even if code delegation is set
                    // - this is a built-in redirect functionality, and it isn't considered code delegation.
                    executableCodeAddress = Address.fromHexString(HAS_EVM_ADDRESS);
                    isCodeDelegation = false;
                } else if (hasCodeDelegation) {

                    executableCodeAddress = Address.wrap(
                            contractAccount.getCode().slice(CodeDelegationHelper.CODE_DELEGATION_PREFIX.size()));
                    LOG.warn("XXX CODE DELEGATION to {}", executableCodeAddress);

                    isCodeDelegation = true;
                } else {
                    executableCodeAddress = frame.getContractAddress();
                    isCodeDelegation = false;
                }
            } else {
                LOG.warn("XXX contractAccount is null");

                // Call target account doesn't exist
                executableCodeAddress = frame.getContractAddress();
                isCodeDelegation = false;
            }

            return new CustomMessageCallContext(
                    frame,
                    tracer,
                    Optional.ofNullable(contractAccount),
                    executableCodeAddress,
                    isCodeDelegation,
                    FrameUtils.transfersValue(frame));
        }
    }

    private final FeatureFlags featureFlags;
    private final AddressChecks addressChecks;
    private final PrecompileContractRegistry precompiles;
    private final Map<Address, HederaSystemContract> systemContracts;
    private final ContractMetrics contractMetrics;

    private enum ForLazyCreation {
        YES,
        NO,
    }

    /**
     * Constructor.
     * @param evm the evm to use in this call
     * @param featureFlags current evm module feature flags
     * @param precompiles the present precompiles
     * @param addressChecks checks against addresses reserved for Hedera
     * @param systemContracts the Hedera system contracts
     */
    public CustomMessageCallProcessor(
            @NonNull final EVM evm,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final PrecompileContractRegistry precompiles,
            @NonNull final AddressChecks addressChecks,
            @NonNull final Map<Address, HederaSystemContract> systemContracts,
            @NonNull final ContractMetrics contractMetrics) {
        super(evm, precompiles);
        this.featureFlags = Objects.requireNonNull(featureFlags);
        this.precompiles = Objects.requireNonNull(precompiles);
        this.addressChecks = Objects.requireNonNull(addressChecks);
        this.systemContracts = Objects.requireNonNull(systemContracts);
        this.contractMetrics = Objects.requireNonNull(contractMetrics);
    }

    /**
     * Starts the execution of a message call based on the contract address of the given frame,
     * or halts the frame with an appropriate reason if this cannot be done.
     *
     * <p>This contract address may reference,
     * <ol>
     *     <li>A Hedera system contract.</li>
     *     <li>A native EVM precompile.</li>
     *     <li>A Hedera system account (up to {@code 0.0.750}).</li>
     *     <li>A valid lazy-creation target address.</li>
     *     <li>An existing contract.</li>
     *     <li>An existing account.</li>
     * </ol>
     *
     * @param frame the frame to start
     * @param tracer the operation tracer
     */
    @Override
    public void start(@NonNull final MessageFrame frame, @NonNull final OperationTracer tracer) {
        final var context = CustomMessageCallContext.create(frame, tracer);

        // This must be done first as the system contract address range overlaps with system
        // accounts. Note that unlike EVM precompiles, we do allow sending value "to" Hedera
        // system contracts because they sometimes require fees greater than be reasonably
        // paid using gas; for example, when creating a new token. But the system contract
        // only diverts this value to the network's fee collection accounts, instead of
        // actually receiving it.

        if (isSystemContractCall(context)) {
            handleSystemContractCall(context);
        } else if (isPrecompileCall(context)) {
            handlePrecompileCall(context);
        } else if (addressChecks.isSystemAccount(context.executableCodeAddress)) {
            // Handle System Account that is neither System Contract nor Precompile
            handleNonExtantSystemAccountCall(context);
        } else {
            handleRegularCall(context);
        }
    }

    private boolean isSystemContractCall(@NonNull final CustomMessageCallContext context) {
        return systemContracts.containsKey(context.executableCodeAddress);
    }

    private void handleSystemContractCall(@NonNull final CustomMessageCallContext context) {
        if (context.isCodeDelegation) {
            final var recipientIsTokenOrScheduleAccount = context.contractAccount.stream()
                    .anyMatch(a -> a instanceof TokenEvmAccount || a instanceof ScheduleEvmAccount);

            if (recipientIsTokenOrScheduleAccount) {
                // Token and Schedule accounts are allowed to delegate code to an actual System Contract call,
                // but value transfer isn't allowed.
                if (context.transfersValue) {
                    doHalt(context, INVALID_CONTRACT_ID);
                } else {
                    doExecuteSystemContract(context, systemContracts.get(context.executableCodeAddress));
                }
            } else {
                // For any other Hedera accounts: code delegation to System Contracts is a no-op - so just succeed.
                context.frame.setState(MessageFrame.State.COMPLETED_SUCCESS);

                // Even though execution is no-op, the call may still carry value transfer.
                if (context.transfersValue) {
                    doTransferValueOrHalt(context);
                }
            }
        } else if (context.transfersValue && !isTokenCreation(context.frame)) {
            // System Contract calls that transfer value aren't allowed, unless they're token creation.
            doHalt(context, INVALID_CONTRACT_ID);
        } else {
            doExecuteSystemContract(context, systemContracts.get(context.executableCodeAddress));
        }
    }

    /**
     * This method is necessary as the system contracts do not calculate their gas requirements until after
     * the call to computePrecompile. Thus, the logic for checking for sufficient gas must be done in a different
     * order vs normal precompiles.
     *
     * @param context the current call context
     * @param systemContract the system contract to execute
     */
    private void doExecuteSystemContract(
            @NonNull final CustomMessageCallContext context, @NonNull final HederaSystemContract systemContract) {
        final var frame = context.frame;
        final var systemContractAddress = context.executableCodeAddress;
        final var fullResult = systemContract.computeFully(
                ContractID.newBuilder()
                        .contractNum(numberOfLongZero(systemContractAddress))
                        .build(),
                frame.getInputData(),
                frame);
        final var gasRequirement = fullResult.gasRequirement();
        final PrecompileContractResult result;
        if (frame.getRemainingGas() < gasRequirement) {
            result = PrecompileContractResult.halt(Bytes.EMPTY, Optional.of(INSUFFICIENT_GAS));
        } else {
            if (!fullResult.isRefundGas()) {
                frame.decrementRemainingGas(gasRequirement);
            }

            final var opsDurationCounter = FrameUtils.opsDurationCounter(frame);
            final var opsDurationSchedule = opsDurationCounter.schedule();
            final var opsDurationCost = gasRequirement
                    * opsDurationSchedule.systemContractGasBasedDurationMultiplier()
                    / opsDurationSchedule.multipliersDenominator();
            opsDurationCounter.recordOpsDurationUnitsConsumed(opsDurationCost);
            contractMetrics
                    .opsDurationMetrics()
                    .recordSystemContractOpsDuration(
                            systemContract.getName(), systemContractAddress.toHexString(), opsDurationCost);

            result = fullResult.result();
        }
        finishPrecompileExecution(context, result, SYSTEM);
    }

    private void finishPrecompileExecution(
            @NonNull final CustomMessageCallContext context,
            @NonNull final PrecompileContractResult result,
            @NonNull final ContractActionType type) {
        final var frame = context.frame;
        if (result.state() == MessageFrame.State.REVERT) {
            frame.setRevertReason(result.output());
        } else {
            frame.setOutputData(result.output());
        }
        frame.setState(result.state());
        frame.setExceptionalHaltReason(result.haltReason());
        ((ActionSidecarContentTracer) context.tracer).tracePrecompileResult(frame, type);
    }

    private boolean isPrecompileCall(@NonNull final CustomMessageCallContext context) {
        final var evmPrecompile = precompiles.get(context.executableCodeAddress);
        return evmPrecompile != null && isPrecompileEnabled(context.executableCodeAddress, context.frame);
    }

    private void handlePrecompileCall(@NonNull final CustomMessageCallContext context) {
        final var evmPrecompile = precompiles.get(context.executableCodeAddress);

        if (context.isCodeDelegation) {
            // Code delegation to Precompile is a no-op - so just succeed.
            context.frame.setState(MessageFrame.State.COMPLETED_SUCCESS);
            // Even though execution is no-op, the call may still carry value transfer.
            if (context.transfersValue) {
                doTransferValueOrHalt(context);
            }
        } else if (context.transfersValue) {
            // Value transfer isn't allowed for precompile calls.
            doHalt(context, INVALID_CONTRACT_ID);
        } else {
            doExecutePrecompile(context, evmPrecompile);
        }
    }

    private void doExecutePrecompile(
            @NonNull final CustomMessageCallContext context, @NonNull final PrecompiledContract precompile) {
        final var frame = context.frame;
        final var gasRequirement = precompile.gasRequirement(frame.getInputData());
        final PrecompileContractResult result;
        if (frame.getRemainingGas() < gasRequirement) {
            result = PrecompileContractResult.halt(Bytes.EMPTY, Optional.of(INSUFFICIENT_GAS));
        } else {
            frame.decrementRemainingGas(gasRequirement);

            final var opsDurationCounter = FrameUtils.opsDurationCounter(frame);
            final var opsDurationSchedule = opsDurationCounter.schedule();
            final var opsDurationCost = gasRequirement
                    * opsDurationSchedule.precompileGasBasedDurationMultiplier()
                    / opsDurationSchedule.multipliersDenominator();
            opsDurationCounter.recordOpsDurationUnitsConsumed(opsDurationCost);
            contractMetrics.opsDurationMetrics().recordPrecompileOpsDuration(precompile.getName(), opsDurationCost);

            result = precompile.computePrecompile(frame.getInputData(), frame);
            if (result.isRefundGas()) {
                frame.incrementRemainingGas(gasRequirement);
            }
        }
        // We must always call tracePrecompileResult() to ensure the tracer is in a consistent
        // state, because AbstractMessageProcessor.process() will not invoke the tracer's
        // tracePostExecution() method unless start() returns with a state of CODE_EXECUTING;
        // but for a precompile call this never happens.
        finishPrecompileExecution(context, result, PRECOMPILE);
    }

    private void handleNonExtantSystemAccountCall(CustomMessageCallContext context) {
        if (context.isCodeDelegation) {
            // Code delegation is a no-op - so just succeed.
            context.frame.setState(MessageFrame.State.COMPLETED_SUCCESS);
            // Even though execution is no-op, the call may still carry value transfer.
            if (context.transfersValue) {
                doTransferValueOrHalt(context);
            }
        } else if (isAllowanceHook(context.frame, context.executableCodeAddress)) {
            // Allowance hook execution is explicitly allowed
            context.frame.setState(MessageFrame.State.CODE_EXECUTING);
        } else if (context.transfersValue) {
            doHalt(context, INVALID_CONTRACT_ID);
        } else {
            final var result = PrecompileContractResult.success(Bytes.EMPTY);
            context.frame.clearGasRemaining();
            finishPrecompileExecution(context, result, PRECOMPILE);
        }
    }

    private void handleRegularCall(@NonNull final CustomMessageCallContext context) {
        LOG.warn("XX handleRegularCall...");
        if (context.transfersValue) {
            doTransferValueOrHalt(context);
            if (alreadyHalted(context.frame)) {
                return;
            }
        }

        // For mono-service fidelity, we need to consider called contracts
        // as a special case eligible for staking rewards
        if (isTopLevelTransaction(context.frame)) {
            context.contractAccount
                    .filter(ProxyEvmContract.class::isInstance)
                    .map(ProxyEvmContract.class::cast)
                    .ifPresent(contract ->
                            recordBuilderFor(context.frame).trackExplicitRewardSituation(contract.hederaId()));
        }

        LOG.warn("XX handleRegularCall setting state to CODE_EXECUTING");
        context.frame.setState(MessageFrame.State.CODE_EXECUTING);
    }

    /**
     * Checks if the message frame is executing a hook dispatch and if the contract address is
     * the allowance hook address
     *
     * @param codeAddress the address of the precompile to check
     * @param frame the current message frame
     * @return true if the frame is executing a hook dispatch and the code address is the allowance hook
     * address, false otherwise
     */
    private static boolean isAllowanceHook(final @NonNull MessageFrame frame, final Address codeAddress) {
        return FrameUtils.isHookExecution(frame) && HTS_HOOKS_CONTRACT_ADDRESS.equals(codeAddress);
    }

    /**
     * Checks if the given message frame is a token creation scenario.
     *
     * <p>This method inspects the first four bytes of the input data of the message frame
     * to determine if it matches any of the known selectors for creating fungible or non-fungible tokens.
     *
     * @param frame the message frame to check
     * @return true if the input data matches any of the known create selectors, false otherwise
     */
    private boolean isTokenCreation(MessageFrame frame) {
        if (frame.getInputData().isEmpty()) {
            return false;
        }
        final var selector = frame.getInputData().slice(0, 4).toArray();
        return createMethodsSet.stream().anyMatch(s -> Arrays.equals(s.selector(), selector));
    }

    /**
     * @return whether the implicit creation is currently enabled
     */
    public boolean isImplicitCreationEnabled() {
        return featureFlags.isImplicitCreationEnabled();
    }

    private void doTransferValueOrHalt(@NonNull final CustomMessageCallContext context) {
        final var frame = context.frame;
        final var proxyWorldUpdater = (ProxyWorldUpdater) frame.getWorldUpdater();
        // Try to lazy-create the recipient address if it doesn't exist
        if (!addressChecks.isPresent(frame.getRecipientAddress(), frame)) {
            final var maybeReasonToHalt = proxyWorldUpdater.tryLazyCreation(frame.getRecipientAddress(), frame);
            maybeReasonToHalt.ifPresent(reason -> doHaltOnFailedLazyCreation(context, reason));
        }
        if (!alreadyHalted(frame)) {
            final var maybeReasonToHalt = proxyWorldUpdater.tryTransfer(
                    frame.getSenderAddress(),
                    frame.getRecipientAddress(),
                    frame.getValue().toLong(),
                    acquiredSenderAuthorizationViaDelegateCall(frame));
            maybeReasonToHalt.ifPresent(reason -> {
                if (reason == INVALID_SIGNATURE) {
                    setPropagatedCallFailure(frame, MISSING_RECEIVER_SIGNATURE);
                }
                doHalt(context, reason);
            });
        }
    }

    private void doHaltOnFailedLazyCreation(
            @NonNull final CustomMessageCallContext context, @NonNull final ExceptionalHaltReason reason) {
        doHalt(context, reason, ForLazyCreation.YES);
    }

    private void doHalt(@NonNull final CustomMessageCallContext context, @NonNull final ExceptionalHaltReason reason) {
        doHalt(context, reason, ForLazyCreation.NO);
    }

    private void doHalt(
            @NonNull final CustomMessageCallContext context,
            @NonNull final ExceptionalHaltReason reason,
            @NonNull final ForLazyCreation forLazyCreation) {
        final var frame = context.frame;
        frame.setState(EXCEPTIONAL_HALT);
        frame.setExceptionalHaltReason(Optional.of(reason));
        if (forLazyCreation == ForLazyCreation.YES) {
            frame.decrementRemainingGas(frame.getRemainingGas());
            if (reason == INSUFFICIENT_CHILD_RECORDS) {
                setPropagatedCallFailure(frame, RESULT_CANNOT_BE_EXTERNALIZED);
            }
        }
        if (forLazyCreation == ForLazyCreation.YES) {
            context.tracer.traceAccountCreationResult(frame, Optional.of(reason));
        } else {
            context.tracer.tracePostExecution(frame, new Operation.OperationResult(frame.getRemainingGas(), reason));
        }
    }
}
