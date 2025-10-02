// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumberedContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.workflows.record.DeleteCapableTransactionStreamBuilder;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class FrameUtils {
    public static final String CONFIG_CONTEXT_VARIABLE = "contractsConfig";
    public static final String TRACKER_CONTEXT_VARIABLE = "storageAccessTracker";
    public static final String ACTION_SIDECARS_VARIABLE = "actionSidecars";
    public static final String ACTION_SIDECARS_VALIDATION_VARIABLE = "actionSidecarsValidation";
    public static final String BYTECODE_SIDECARS_VARIABLE = "bytecodeSidecars";
    public static final String TINYBAR_VALUES_CONTEXT_VARIABLE = "tinybarValues";
    public static final String HAPI_RECORD_BUILDER_CONTEXT_VARIABLE = "hapiRecordBuilder";
    public static final String PROPAGATED_CALL_FAILURE_CONTEXT_VARIABLE = "propagatedCallFailure";
    public static final String SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE = "systemContractGasCalculator";
    public static final String PENDING_CREATION_BUILDER_CONTEXT_VARIABLE = "pendingCreationBuilder";
    public static final String OPS_DURATION_COUNTER = "opsDurationCounter";
    public static final String IS_HOOK_VARIABLE = "hook";

    public enum EntityType {
        TOKEN,
        SCHEDULE_TXN,
        REGULAR_ACCOUNT
    }

    private FrameUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static @NonNull Configuration configOf(@NonNull final MessageFrame frame) {
        return requireNonNull(initialFrameOf(frame).getContextVariable(CONFIG_CONTEXT_VARIABLE));
    }

    public static @NonNull ContractsConfig contractsConfigOf(@NonNull final MessageFrame frame) {
        return configOf(frame).getConfigData(ContractsConfig.class);
    }

    public static boolean hasBytecodeSidecarsEnabled(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).hasContextVariable(BYTECODE_SIDECARS_VARIABLE);
    }

    public static boolean hasActionSidecarsEnabled(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).hasContextVariable(ACTION_SIDECARS_VARIABLE);
    }

    public static boolean hasActionValidationEnabled(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).hasContextVariable(ACTION_SIDECARS_VALIDATION_VARIABLE);
    }

    public static boolean hasValidatedActionSidecarsEnabled(@NonNull final MessageFrame frame) {
        final var initialFrame = initialFrameOf(frame);
        return initialFrame.hasContextVariable(ACTION_SIDECARS_VARIABLE)
                && initialFrame.hasContextVariable(ACTION_SIDECARS_VALIDATION_VARIABLE);
    }

    public static @Nullable StorageAccessTracker accessTrackerFor(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).getContextVariable(TRACKER_CONTEXT_VARIABLE);
    }

    /**
     * Sets a context variable with a Hedera-specific propagated failure reason indicating that the transaction
     * executing the given frame just experienced such a message call failure.
     *
     * @param frame a frame in the transaction of interest
     * @param failure the propagated failure reason
     */
    public static void setPropagatedCallFailure(
            @NonNull final MessageFrame frame, @NonNull final HevmPropagatedCallFailure failure) {
        requireNonNull(frame);
        requireNonNull(failure);
        propagatedCallFailureReference(frame).set(failure);
    }

    /**
     * Gets and clears any propagated call failure from the context variable in the transaction containing
     * the given frame.
     *
     * @param frame a frame in the transaction of interest
     */
    public static @NonNull HevmPropagatedCallFailure getAndClearPropagatedCallFailure(
            @NonNull final MessageFrame frame) {
        requireNonNull(frame);
        return propagatedCallFailureReference(frame).getAndClear();
    }

    /**
     * Gets and clears any metadata for a pending creation in the context of the given frame.
     *
     * @param frame a frame in the transaction of interest
     * @param contractID the contract id of the pending creation
     * @return the metadata for the pending creation
     */
    public static @NonNull PendingCreationMetadata getAndClearPendingCreationMetadata(
            @NonNull final MessageFrame frame, @NonNull final ContractID contractID) {
        requireNonNull(frame);
        requireNonNull(contractID);
        return pendingCreationMetadataRef(frame).getAndClearOrThrowFor(contractID);
    }

    public static @NonNull ProxyWorldUpdater proxyUpdaterFor(@NonNull final MessageFrame frame) {
        return (ProxyWorldUpdater) frame.getWorldUpdater();
    }

    public static @NonNull TinybarValues tinybarValuesFor(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).getContextVariable(TINYBAR_VALUES_CONTEXT_VARIABLE);
    }

    /**
     * Returns a record builder able to track the beneficiaries of {@code SELFDESTRUCT} operations executed
     * so far in the frame's EVM transaction.
     *
     * <p>Note it does not matter if we track a {@code SELFDESTRUCT} that is later reverted; we just need to
     * be sure that for the committed self-destructs, we know what beneficiary they used so the staking logic
     * can redirect rewards as appropriate.
     *
     * @param frame the frame whose EVM transaction we are tracking beneficiaries in
     * @return the record builder able to track beneficiary ids
     */
    public static @NonNull DeleteCapableTransactionStreamBuilder selfDestructBeneficiariesFor(
            @NonNull final MessageFrame frame) {
        return requireNonNull(initialFrameOf(frame).getContextVariable(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE));
    }

    /**
     * Returns true if the given frame has a record builder.
     *
     * @param frame the frame to check
     * @return true if the frame has a record builder
     */
    public static boolean isTopLevelTransaction(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).hasContextVariable(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE);
    }

    /**
     * Returns a record builder able to track the contracts called in the frame's
     * EVM transaction.
     *
     * @param frame the frame whose EVM transaction we are tracking called contracts in
     * @return the record builder
     */
    public static @NonNull ContractOperationStreamBuilder recordBuilderFor(@NonNull final MessageFrame frame) {
        return requireNonNull(initialFrameOf(frame).getContextVariable(HAPI_RECORD_BUILDER_CONTEXT_VARIABLE));
    }

    public static @NonNull SystemContractGasCalculator systemContractGasCalculatorOf(
            @NonNull final MessageFrame frame) {
        return initialFrameOf(frame).getContextVariable(SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE);
    }

    /**
     * Returns true if the given frame achieved its sender authorization via a delegate call.
     *
     * <p>That is, returns true if the frame's <i>parent</i> was executing code via a
     * {@code DELEGATECALL} (or chain of {@code DELEGATECALL}'s); and the delegated code
     * contained a {@code CALL}, {@code CALLCODE}, or {@code DELEGATECALL} instruction. In
     * this case, the frame's sender is the recipient of the parent frame; the same as if the
     * parent frame directly initiated a call. But our {@link com.hedera.hapi.node.base.Key}
     * types are designed to enforce stricter permissions here, even though the sender address
     * is the same.
     *
     * <p>In particular, if a contract {@code 0xabcd} initiates a call directly, then it
     * can "activate" the signature of any {@link com.hedera.hapi.node.base.Key.KeyOneOfType#CONTRACT_ID}
     * or {@link com.hedera.hapi.node.base.Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID} key needed
     * to authorize the call. But if its delegated code initiates a call, then it should activate
     * <b>only</b> signatures of keys of type {@link com.hedera.hapi.node.base.Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}.
     *
     * <p>We thus use this helper in the {@link CustomMessageCallProcessor} to detect when an
     * initiated call is being initiated by delegated code; and enforce the stricter permissions.
     *
     * @param frame the frame to check
     * @return true if the frame achieved its sender authorization via a delegate call
     */
    public static boolean acquiredSenderAuthorizationViaDelegateCall(@NonNull final MessageFrame frame) {
        final var iter = frame.getMessageFrameStack().iterator();
        // Always skip the current frame
        final var executingFrame = iter.next();
        if (frame != executingFrame) {
            // This should be impossible
            throw new IllegalArgumentException(
                    "Only the executing frame should be tested for delegate sender authorization");
        }
        if (!iter.hasNext()) {
            // The current frame is the initial frame, and thus not initiated from delegated code
            return false;
        }
        final var parent = iter.next();
        return isDelegateCall(parent);
    }

    public static boolean isDelegateCall(@NonNull final MessageFrame frame) {
        return !frame.getRecipientAddress().equals(frame.getContractAddress());
    }

    public static boolean transfersValue(@NonNull final MessageFrame frame) {
        return !frame.getValue().isZero();
    }

    public static boolean alreadyHalted(@NonNull final MessageFrame frame) {
        return frame.getState() == MessageFrame.State.EXCEPTIONAL_HALT;
    }

    public static Optional<MessageFrame> maybeNext(@NonNull final MessageFrame frame) {
        final var stack = frame.getMessageFrameStack();
        final var frames = stack.iterator();
        return frames.hasNext() ? Optional.of(frames.next()) : Optional.empty();
    }

    /**
     * Given a frame and an address, returns whether any frame in its
     * stack has the given receiver address.
     *
     * @param frame the frame whose stack to travers
     * @param address the receiver address to seek
     * @return if the stack includes a frame with the given receive
     */
    public static boolean stackIncludesActiveAddress(
            @NonNull final MessageFrame frame, @NonNull final Address address) {
        final var iter = frame.getMessageFrameStack().iterator();
        // We skip the frame at the top of the stack (recall that a deque representing
        // a stack stores the top at the front of its internal list)
        for (iter.next(); iter.hasNext(); ) {
            final var ancestor = iter.next();
            if (address.equals(ancestor.getRecipientAddress())) {
                return true;
            }
        }
        return false;
    }

    public enum CallType {
        QUALIFIED_DELEGATE,
        UNQUALIFIED_DELEGATE,
        DIRECT_OR_PROXY_REDIRECT,
    }

    public static CallType callTypeOf(final MessageFrame frame, final EntityType expectedEntityType) {
        if (!isDelegateCall(frame)) {
            return CallType.DIRECT_OR_PROXY_REDIRECT;
        }
        final var recipient = frame.getRecipientAddress();
        // Evaluate whether the recipient is either a token or on the permitted callers list.
        // This determines if we should treat this as a delegate call.
        // We accept delegates if the entity redirect contract calls us.
        final CallType viableType;
        if (isExpectedEntityType(frame, recipient, expectedEntityType)) {
            viableType = CallType.DIRECT_OR_PROXY_REDIRECT;
        } else if (isQualifiedDelegate(recipient, frame)) {
            viableType = CallType.QUALIFIED_DELEGATE;
        } else {
            return CallType.UNQUALIFIED_DELEGATE;
        }
        // make sure we have a parent calling context
        return validateParentCallType(frame, viableType);
    }

    private static CallType validateParentCallType(@NonNull MessageFrame frame, @NonNull CallType viableType) {
        requireNonNull(frame);
        requireNonNull(viableType);

        // make sure we have a parent calling context
        final var stack = frame.getMessageFrameStack();
        final var frames = stack.iterator();
        frames.next();
        if (!frames.hasNext()) {
            // Impossible to get here w/o a catastrophic EVM bug
            throw new IllegalStateException("No parent frame for delegate call");
        }
        // Even a qualified delegatecall must originate from a non-delegatecall
        return isDelegateCall(frames.next()) ? CallType.UNQUALIFIED_DELEGATE : viableType;
    }

    /**
     * Returns true if the given frame is a call to a contract that must be present based on feature flag settings.
     *
     * @param frame the current message frame
     * @param address to check for possible grandfathering
     * @param featureFlags current evm module feature flags
     * @return true if the contract address must be present in the ledger
     */
    public static boolean contractRequired(
            @NonNull final MessageFrame frame,
            @NonNull final Address address,
            @NonNull final FeatureFlags featureFlags) {
        requireNonNull(frame);
        requireNonNull(address);
        requireNonNull(featureFlags);

        Long maybeGrandfatheredNumber = null;
        if (isLongZero(address)) {
            try {
                maybeGrandfatheredNumber =
                        asNumberedContractId(entityIdFactory(frame), address).contractNum();
            } catch (final ArithmeticException ignore) {
                // Not a valid numbered contract id
            }
        }
        return !featureFlags.isAllowCallsToNonContractAccountsEnabled(
                contractsConfigOf(frame), maybeGrandfatheredNumber);
    }

    /**
     * Returns true if the given recipient address to the frame is of the expected entity type.
     * @param frame current message frame
     * @param address address to check
     * @param expectedEntity expected entity type
     * @return true if the address is of the expected entity type
     */
    private static boolean isExpectedEntityType(
            final MessageFrame frame, final Address address, final EntityType expectedEntity) {
        requireNonNull(frame);
        requireNonNull(address);

        final var updater = (ProxyWorldUpdater) frame.getWorldUpdater();
        final var recipient = updater.getHederaAccount(address);
        if (recipient != null) {
            return switch (expectedEntity) {
                case TOKEN -> recipient.isTokenFacade();
                case SCHEDULE_TXN -> recipient.isScheduleTxnFacade();
                case REGULAR_ACCOUNT -> recipient.isRegularAccount();
            };
        }
        return false;
    }

    public static @NonNull MessageFrame initialFrameOf(@NonNull final MessageFrame frame) {
        final var stack = frame.getMessageFrameStack();
        return stack.isEmpty() ? frame : stack.getLast();
    }

    private static PropagatedCallFailureRef propagatedCallFailureReference(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).getContextVariable(PROPAGATED_CALL_FAILURE_CONTEXT_VARIABLE);
    }

    private static PendingCreationMetadataRef pendingCreationMetadataRef(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).getContextVariable(PENDING_CREATION_BUILDER_CONTEXT_VARIABLE);
    }

    private static boolean isQualifiedDelegate(@NonNull final Address recipient, @NonNull final MessageFrame frame) {
        return isLongZero(recipient)
                && contractsConfigOf(frame).permittedDelegateCallers().contains(numberOfLongZero(recipient));
    }

    public static boolean isPrecompileEnabled(
            @NonNull final Address precompileAddress, @NonNull final MessageFrame frame) {
        return contractsConfigOf(frame).disabledPrecompiles().stream()
                .map(Address::precompiled)
                .filter(precompileAddress::equals)
                .findAny()
                .isEmpty();
    }
    /**
     * Returns true if the given frame is part of a hook execution.
     *
     * @param frame the current frame
     * @return true if the frame is part of a hook execution
     */
    public static boolean isHookExecution(@NonNull final MessageFrame frame) {
        return Boolean.TRUE.equals(initialFrameOf(frame).getContextVariable(IS_HOOK_VARIABLE));
    }

    /**
     * Returns the {@link EntityIdFactory}
     *
     * @param frame the current frame
     * @return the entity id factory
     */
    public static EntityIdFactory entityIdFactory(@NonNull final MessageFrame frame) {
        return proxyUpdaterFor(frame).entityIdFactory();
    }

    /**
     * Returns the Hedera ops duration throttle from the top level frame.
     *
     * @param frame the current frame
     * @return the total Hedera ops throttle
     */
    public static OpsDurationCounter opsDurationCounter(@NonNull final MessageFrame frame) {
        return initialFrameOf(frame).getContextVariable(OPS_DURATION_COUNTER);
    }
}
