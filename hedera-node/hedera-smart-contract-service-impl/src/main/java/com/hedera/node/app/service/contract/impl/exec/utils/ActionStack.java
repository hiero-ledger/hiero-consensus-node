// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.hapi.streams.CallOperationType.OP_CALL;
import static com.hedera.hapi.streams.CallOperationType.OP_CREATE;
import static com.hedera.hapi.streams.ContractActionType.CALL;
import static com.hedera.hapi.streams.ContractActionType.CREATE;
import static com.hedera.hapi.streams.codec.ContractActionProtoCodec.RECIPIENT_UNSET;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.hederaIdNumOfContractIn;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.hederaIdNumOfOriginatorIn;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.MessageFrame.Type.CONTRACT_CREATION;
import static org.hyperledger.besu.evm.frame.MessageFrame.Type.MESSAGE_CALL;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.CallOperationType;
import com.hedera.hapi.streams.ContractAction;
import com.hedera.hapi.streams.ContractActionType;
import com.hedera.node.app.service.contract.impl.utils.OpcodeUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Encapsulates a stack of contract actions.
 */
public class ActionStack {
    private static final Logger log = LogManager.getLogger(ActionStack.class);

    private final ActionsHelper helper;
    private final List<ActionWrapper> allActions;
    private final Deque<ActionWrapper> actionsStack;
    private final List<ActionWrapper> invalidActions;

    /**
     * Controls whether the stack should validate the next action it is finalizing.
     */
    public enum Validation {
        /**
         * Validate next action
         */
        ON,
        /**
         * Do not validate next action
         */
        OFF
    }

    /**
     * Default constructor.
     */
    public ActionStack() {
        this(new ActionsHelper(), new ArrayList<>(), new ArrayDeque<>(), new ArrayList<>());
    }

    /**
     * Convenience constructor for testing.
     *
     * @param helper the helper to use for action creation
     * @param allActions the container to use for all tracked actions
     * @param actionsStack the stack to use for actions
     * @param invalidActions the container to use for invalid actions
     */
    public ActionStack(
            @NonNull final ActionsHelper helper,
            @NonNull final List<ActionWrapper> allActions,
            @NonNull final Deque<ActionWrapper> actionsStack,
            @NonNull final List<ActionWrapper> invalidActions) {
        this.helper = helper;
        this.invalidActions = invalidActions;
        this.allActions = allActions;
        this.actionsStack = actionsStack;
    }

    /**
     * Returns a view of this stack appropriate for externalizing in a
     * {@link com.hedera.hapi.streams.SidecarType#CONTRACT_ACTION} sidecar.
     *
     * @return a view of this stack ready to be put in a sidecar
     */
    public @NonNull List<ContractAction> asContractActions() {
        return allActions.stream().map(ActionWrapper::get).toList();
    }

    /**
     * Finalizes the last action created in this stack in the context of the given frame; and
     * also pops the action from the stack if it is topmost. The nature of finalization work
     * depends on the state of the given frame.
     * <ul>
     *     <li>For the {@code NOT_STARTED}, {@code CODE_EXECUTING}, and {@code CODE_SUSPENDED}
     *     states, finalization is a no-op (these are not even final states, really).</li>
     *     <li>For the {@code CODE_SUCCESS} and {@code COMPLETED_SUCCESS} states, finalization
     *     sets the gas used and the output data; and for calls that performed a lazy creation,
     *     the details of that creation are also set.</li>
     *     <li>For the {@code REVERT} state, finalization again sets gas used, along with the
     *     revert reason; and for a reverted creation, nulls out the recipient contract.</li>
     *     <li>For {@code EXCEPTIONAL_HALT} and {@code COMPLETED_FAILED} states, finalization
     *     again sets gas used and nulls out the recipient contract for a halted creation; and
     *     sets the error causing the failure. When the error is {@code INVALID_SOLIDITY_ADDRESS},
     *     also constructs a synthetic action to represent the invalid call.
     * </ul>
     *  @param frame      the frame to use for finalization context
     *
     * @param validation whether to validate the final action
     */
    public void finalizeLastAction(@NonNull final MessageFrame frame, @NonNull final Validation validation) {
        internalFinalize(validation, frame);
    }

    /**
     * Does the same work as {@link #finalizeLastAction(MessageFrame, Validation)}, but takes a couple
     * extra steps to ensure the final action is customized for the given precompile type.
     *
     * @param frame the frame to use for finalization context
     * @param type the finalized action's precompile type
     * @param validation whether to validate the final action
     */
    public void finalizeLastStackActionAsPrecompile(
            @NonNull final MessageFrame frame,
            @NonNull final ContractActionType type,
            @NonNull final Validation validation) {
        internalFinalize(validation, frame, action -> action.copyBuilder()
                .recipientContract(entityIdFactory(frame).newContractId(numberOfLongZero(frame.getContractAddress())))
                .callType(type)
                .build());
    }

    private void internalFinalize(@NonNull final Validation validateAction, @NonNull final MessageFrame frame) {
        internalFinalize(validateAction, frame, null);
    }

    private void internalFinalize(
            @NonNull final Validation validateAction,
            @NonNull final MessageFrame frame,
            @Nullable final UnaryOperator<ContractAction> transform) {
        requireNonNull(frame);

        // Try to get the action from the stack or the list as requested; warn and return if not found
        final ActionWrapper lastWrappedAction;
        if (actionsStack.isEmpty()) {
            log.warn("Action stack prematurely empty ({})", () -> formatFrameContextForLog(frame));
            return;
        } else {
            lastWrappedAction = actionsStack.pop();
        }

        // Swap in the final form of the action
        final var finalAction = finalFormOf(lastWrappedAction.get(), frame);
        lastWrappedAction.set(transform == null ? finalAction : transform.apply(finalAction));

        // Validate and track problems if applicable
        if (validateAction == Validation.ON && !helper.isValid(lastWrappedAction.get())) {
            invalidActions.add(lastWrappedAction);
        }
    }

    private ContractAction finalFormOf(@NonNull final ContractAction action, @NonNull final MessageFrame frame) {
        return switch (frame.getState()) {
            case NOT_STARTED, CODE_EXECUTING, CODE_SUSPENDED -> action;
            case CODE_SUCCESS, COMPLETED_SUCCESS -> {
                final var builder = action.copyBuilder();
                builder.gasUsed(action.gas() - frame.getRemainingGas());
                if (action.callType() == CREATE) {
                    builder.output(Bytes.EMPTY);
                } else {
                    builder.output(tuweniToPbjBytes(frame.getOutputData()));
                    if (action.targetedAddress() != null) {
                        final var maybeCreatedAddress = pbjToBesuAddress(action.targetedAddressOrThrow());
                        final var maybeCreatedAccount = proxyUpdaterFor(frame).getHederaAccount(maybeCreatedAddress);
                        // Fill in the account of id of a successful lazy creation; but just leave
                        // the targeted address in case of a failed lazy-creation or a call to a
                        // non-existent address
                        if (maybeCreatedAccount != null) {
                            builder.recipientAccount(maybeCreatedAccount.hederaId());
                        }
                    }
                }
                yield builder.build();
            }
            case REVERT -> {
                final var builder = action.copyBuilder();
                builder.gasUsed(action.gas() - frame.getRemainingGas());
                frame.getRevertReason()
                        .ifPresentOrElse(
                                reason -> builder.revertReason(tuweniToPbjBytes(reason)),
                                () -> builder.revertReason(Bytes.EMPTY));
                yield withUnsetRecipientIfNeeded(frame.getType(), builder);
            }
            case EXCEPTIONAL_HALT, COMPLETED_FAILED -> {
                final var builder = action.copyBuilder();
                builder.gasUsed(action.gas());
                final var maybeHaltReason = frame.getExceptionalHaltReason();
                if (maybeHaltReason.isPresent()) {
                    final var haltReason = maybeHaltReason.get();
                    builder.error(Bytes.wrap(haltReason.name().getBytes(UTF_8)));
                    if (CALL.equals(action.callType()) && haltReason == INVALID_SOLIDITY_ADDRESS) {
                        final var invalidAddressContext = FrameUtils.invalidAddressContext(frame);
                        // Only create the synth action if the invalid address was actually a call target
                        if (InvalidAddressContext.InvalidAddressType.InvalidCallTarget.equals(
                                invalidAddressContext.type())) {
                            allActions.add(new ActionWrapper(helper.createSynthActionForMissingAddressIn(
                                    frame, invalidAddressContext.culpritAddress())));
                        }
                    }
                } else {
                    builder.error(Bytes.EMPTY);
                }
                yield withUnsetRecipientIfNeeded(frame.getType(), builder);
            }
        };
    }

    /**
     * Given a {@link MessageFrame} which should be the initial frame for a HAPI
     * contract operation, pushes its action onto the stack.
     *
     * <p>The action's type will derive from the type of the frame itself
     * ({@code CONTRACT_CREATION} or {@code MESSAGE_CALL}); and the action's
     * calling account will derive from the frame's
     * {@link MessageFrame#getOriginatorAddress()}.
     *
     * @param frame the initial frame of a HAPI contract operation
     */
    public void pushActionOfTopLevel(@NonNull final MessageFrame frame) {
        final var builder = ContractAction.newBuilder()
                .callOperationType(asCallOperationType(frame.getType()))
                .callingAccount(accountIdWith(frame, hederaIdNumOfOriginatorIn(frame)));
        completePush(builder, frame);
    }

    /**
     * Given a {@link MessageFrame} which should be an intermediate frame
     * for a contract operation, pushes its action onto the stack.
     *
     * <p>The action's type will derive from the opcode being executed in the {@code frame};
     * and the calling contract will be the {@link MessageFrame#getContractAddress()} of the
     * given {@code frame}.
     *
     * @param frame the frame executing an action
     */
    public void pushActionOfIntermediate(@NonNull final MessageFrame frame) {
        final var builder = ContractAction.newBuilder()
                .callOperationType(OpcodeUtils.asCallOperationType(
                        frame.getCurrentOperation().getOpcode()))
                .callingContract(contractIdWith(frame, hederaIdNumOfContractIn(frame)));
        completePush(builder, requireNonNull(frame.getMessageFrameStack().peek()));
    }

    private void completePush(@NonNull ContractAction.Builder builder, @NonNull final MessageFrame frame) {
        builder.callType(asActionType(frame.getType()))
                .gas(frame.getRemainingGas())
                .input(tuweniToPbjBytes(frame.getInputData()))
                .value(frame.getValue().toLong())
                .callDepth(frame.getDepth());
        // If this call "targets" a missing address, we can't decide yet whether to use a contract id or an
        // account id for the recipient; only later when we know whether the call attempted a lazy creation
        // can we decide to either leave this address (on failure) or replace it with the created account id
        if (targetsMissingAddress(frame)) {
            builder.targetedAddress(tuweniToPbjBytes(frame.getContractAddress()));
        } else if (CodeV0.EMPTY_CODE.equals(frame.getCode())) {
            builder.recipientAccount(accountIdWith(frame, hederaIdNumOfContractIn(frame)));
        } else {
            try {
                builder.recipientContract(contractIdWith(frame, hederaIdNumOfContractIn(frame)));
            } catch (NullPointerException ignore) {
                builder.targetedAddress(tuweniToPbjBytes(frame.getContractAddress()));
            }
        }
        final var wrappedAction = new ActionWrapper(builder.build());
        allActions.add(wrappedAction);
        actionsStack.push(wrappedAction);
    }

    /**
     * Given the initial {@link MessageFrame} for all operations applied to this stack,
     * sanitizes the final actions and logs any anomalies.
     * @param frame the initial message frame applied to this stack
     * @param log the logger
     * @param level the logger level
     */
    public void sanitizeFinalActionsAndLogAnomalies(
            @NonNull final MessageFrame frame, @NonNull final Logger log, @NonNull final Level level) {
        // Two possible error conditions are that invalid actions (mainly `oneof` fields that
        // don't have _exactly_ one alternative set) are added to the set of actions (which can
        // cause problems when ingesting sidecar records elsewhere) and that the current actions
        // stack is out-of-sync (due to some error elsewhere).  (The latter condition has not yet
        // been observed outside deliberate fault injection and probably doesn't happen in a
        // running system.)
        if (!actionsStack.isEmpty() || !invalidActions.isEmpty()) {
            final var anomalies = formatAnomaliesAtFinalizationForLog();
            final var frameContext = formatFrameContextForLog(frame);
            log.atLevel(level).log("Invalid at end of EVM run: {} ({})", anomalies, frameContext);
        }

        // Keep only _valid_ actions (this avoids problems when _ingesting_ action sidecars)
        if (!invalidActions.isEmpty()) {
            allActions.removeAll(invalidActions);
            invalidActions.clear();
        }
    }

    private ContractActionType asActionType(final MessageFrame.Type type) {
        return switch (type) {
            case CONTRACT_CREATION -> CREATE;
            case MESSAGE_CALL -> CALL;
        };
    }

    private CallOperationType asCallOperationType(final MessageFrame.Type type) {
        return type == CONTRACT_CREATION ? OP_CREATE : OP_CALL;
    }

    private boolean isMissing(@NonNull final MessageFrame frame, @NonNull final Address address) {
        return frame.getWorldUpdater().get(address) == null;
    }

    private AccountID accountIdWith(@NonNull final MessageFrame frame, final long num) {
        return entityIdFactory(frame).newAccountId(num);
    }

    private ContractID contractIdWith(@NonNull final MessageFrame frame, final long num) {
        return entityIdFactory(frame).newContractId(num);
    }

    private ContractAction withUnsetRecipientIfNeeded(
            @NonNull MessageFrame.Type type, @NonNull final ContractAction.Builder builder) {
        final var action = builder.build();
        return (type == CONTRACT_CREATION) ? withUnsetRecipient(action) : action;
    }

    // (FUTURE) Use builder for simplicity when PBJ lets us set the oneof recipient to UNSET;
    // c.f., https://github.com/hashgraph/pbj/issues/160
    private ContractAction withUnsetRecipient(@NonNull final ContractAction action) {
        return new ContractAction(
                action.callType(),
                action.caller(),
                action.gas(),
                action.input(),
                RECIPIENT_UNSET,
                action.value(),
                action.gasUsed(),
                action.resultData(),
                action.callDepth(),
                action.callOperationType());
    }

    private boolean targetsMissingAddress(@NonNull final MessageFrame frame) {
        return frame.getType() == MESSAGE_CALL && isMissing(frame, frame.getContractAddress());
    }

    private String formatAnomaliesAtFinalizationForLog() {
        final var msgs = new ArrayList<String>();
        if (!actionsStack.isEmpty())
            msgs.add("currentActionsStack not empty, has %d elements left".formatted(actionsStack.size()));
        if (!invalidActions.isEmpty()) {
            msgs.add("of %d actions given, %d were invalid".formatted(this.allActions.size(), invalidActions.size()));
            for (final var ia : invalidActions) {
                msgs.add("invalid: %s".formatted(helper.prettyPrint(ia.get())));
            }
        }
        return String.join("; ", msgs);
    }

    private static String formatFrameContextForLog(@NonNull final MessageFrame frame) {
        final Function<Address, String> addressToString = Address::toUnprefixedHexString;

        final var originator = get(frame, MessageFrame::getOriginatorAddress, addressToString);
        final var sender = get(frame, MessageFrame::getSenderAddress, addressToString);
        final var recipient = get(frame, MessageFrame::getRecipientAddress, addressToString);
        final var contract = get(frame, MessageFrame::getContractAddress, addressToString);
        final var type = get(frame, MessageFrame::getType, Object::toString);
        final var state = get(frame, MessageFrame::getState, Object::toString);

        return "originator %s sender %s recipient %s contract %s type %s state %s"
                .formatted(originator, sender, recipient, contract, type, state);
    }

    private static <E, I> String get(
            @NonNull final E subject, final Function<E, I> getter, final Function<I, String> processor) {
        return processor.compose(getter).apply(subject);
    }
}
