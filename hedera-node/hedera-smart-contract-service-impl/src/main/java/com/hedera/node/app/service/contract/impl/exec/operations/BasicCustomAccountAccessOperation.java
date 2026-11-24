// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractRequired;
import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isDeficientGas;
import static com.hedera.node.app.service.contract.impl.exec.utils.OperationUtils.isWarmAccess;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext.InvalidAddressType;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * Interface to avoid duplicating the same {@link Operation#execute(MessageFrame, EVM)} override in the Hedera
 * customizations of the account access operations {@link CustomBalanceOperation}, {@link CustomExtCodeSizeOperation},
 * {@link CustomExtCodeHashOperation}, and {@link CustomExtCodeCopyOperation}. Each of these,
 * <ol>
 *     <li>Prices the access warm or cold as per EIP-2929, and halts if the frame lacks that gas;</li>
 *     <li>Short-circuits an access to an address reserved by Hedera to a fixed result, still warming the
 *     address for the rest of the transaction; and</li>
 *     <li>Otherwise halts with {@link com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason#INVALID_SOLIDITY_ADDRESS}
 *     if the address must be present but is not, before delegating to the Besu operation.</li>
 * </ol>
 */
public interface BasicCustomAccountAccessOperation {
    /**
     * Response for underflow.
     */
    Operation.OperationResult UNDERFLOW_RESPONSE =
            new Operation.OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

    /**
     * Returns the {@link AddressChecks} instance used to classify the accessed address.
     *
     * @return the {@link AddressChecks} instance used to classify the accessed address
     */
    AddressChecks addressChecks();

    /**
     * Returns the {@link FeatureFlags} instance.
     *
     * @return the {@link FeatureFlags} instance
     */
    FeatureFlags featureFlags();

    /**
     * Returns the gas calculator used for gas cost calculations.
     *
     * @return the gas calculator
     */
    GasCalculator gasCalculator();

    /**
     * Returns the cost of the access given whether the accessed address is warm, with the operation's
     * stack items still in place.
     *
     * @param frame the frame in which the access is being made
     * @param accountIsWarm whether the accessed address is warm
     * @return the gas cost
     */
    long cost(@NonNull MessageFrame frame, boolean accountIsWarm);

    /**
     * Executes the Besu operation being customized.
     *
     * @param frame the frame in which the access is being made
     * @param evm the EVM in which the access is being made
     * @return the result of the operation
     */
    Operation.OperationResult executeUnchecked(@NonNull MessageFrame frame, @NonNull EVM evm);

    /**
     * Returns the address being accessed, which is the top stack item, without consuming it.
     *
     * @param frame the frame in which the access is being made
     * @return the address being accessed
     */
    default Address address(@NonNull final MessageFrame frame) {
        return Words.toAddress(frame.getStackItem(0));
    }

    /**
     * Returns true if an access to the given address is short-circuited to a fixed result; by default,
     * for every long-zero address of an account below {@code 0.0.1001}.
     *
     * @param address the address being accessed
     * @return true if the access is short-circuited
     */
    default boolean isShortCircuited(@NonNull final Address address) {
        return addressChecks().isNonUserAccount(address);
    }

    /**
     * Completes a short-circuited access by consuming the operation's stack items and producing its fixed
     * result; by default, by replacing the accessed address on the stack with zero.
     *
     * @param frame the frame in which the access is being made
     */
    default void shortCircuit(@NonNull final MessageFrame frame) {
        frame.popStackItem();
        frame.pushStackItem(UInt256.ZERO);
    }

    /**
     * Returns the result of an access with too few stack items.
     *
     * @return the result of an access with too few stack items
     */
    default Operation.OperationResult underflowResponse() {
        return UNDERFLOW_RESPONSE;
    }

    /**
     * The basic Hedera-specific override of {@link Operation#execute(MessageFrame, EVM)} for an account access
     * operation; see the class-level documentation for the steps it takes.
     *
     * @param frame the frame in which the access is being made
     * @param evm the EVM in which the access is being made
     * @return the result of the operation
     */
    default Operation.OperationResult executeChecked(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        requireNonNull(frame);
        requireNonNull(evm);
        try {
            final var address = address(frame);
            // Price the access warm or cold as per EIP-2929 before any Hedera-specific checks, so
            // that an already warm address is never halted for lacking the cold access cost
            final long cost = cost(frame, isWarmAccess(frame, address, gasCalculator()));
            if (isDeficientGas(frame, cost)) {
                return new Operation.OperationResult(cost, ExceptionalHaltReason.INSUFFICIENT_GAS);
            }
            if (isShortCircuited(address)) {
                // The access still warms the address for the rest of the transaction (EIP-2929)
                frame.warmUpAddress(address);
                shortCircuit(frame);
                return new Operation.OperationResult(cost, null);
            }
            // Otherwise the address must be present, unless calls to non-contract accounts are allowed
            if (contractRequired(frame, address, featureFlags())
                    && !addressChecks().isPresent(address, frame)) {
                FrameUtils.invalidAddressContext(frame).set(address, InvalidAddressType.NON_CALL_TARGET);
                return new Operation.OperationResult(cost, INVALID_SOLIDITY_ADDRESS);
            }
            return executeUnchecked(frame, evm);
        } catch (UnderflowException ignore) {
            return underflowResponse();
        }
    }
}
