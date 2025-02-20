// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.SELFDESTRUCT;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.isDelegateCall;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.frame.MessageFrame.State;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.AbstractOperation;
import org.hyperledger.besu.evm.operation.SelfDestructOperation;

/**
 * Hedera {@link SelfDestructOperation} that checks whether there is a Hedera-specific reason to halt
 * execution before proceeding with a self-destruct that uses
 * {@link ProxyWorldUpdater#tryTransfer(Address, Address, long, boolean)}.
 * instead of direct {@link MutableAccount#setBalance(Wei)} calls to
 * ensure Hedera signing requirements are enforced.
 */
public class CustomSelfDestructOperation extends AbstractOperation {
    private static final OperationResult UNDERFLOW_RESPONSE =
            new OperationResult(0, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);

    /** EIP-6780 changed SELFDESTRUCT semantics so that it always sweeps, but only deletes the
     * contract if the contract was created in the same transaction.
     */
    public enum UseEIP6780Semantics {
        /**
         * Use EIP-6780 behaviour
         */
        NO,
        /**
         * Do not use EIP-6780 behaviour
         */
        YES
    };

    private final UseEIP6780Semantics eip6780Semantics;

    private final AddressChecks addressChecks;

    /**
     * Constructor
     * @param gasCalculator the gas calculator to be used
     * @param addressChecks checks against addresses reserved for Hedera
     * @param eip6780Semantics whether to use EIP-6780 behaviour
     */
    public CustomSelfDestructOperation(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final AddressChecks addressChecks,
            final UseEIP6780Semantics eip6780Semantics) {
        super(SELFDESTRUCT.opcode(), "SELFDESTRUCT", 1, 0, gasCalculator);
        this.eip6780Semantics = eip6780Semantics;
        this.addressChecks = addressChecks;
    }

    @Override
    public OperationResult execute(@NonNull final MessageFrame frame, @NonNull final EVM evm) {
        try {
            final var beneficiaryAddress = Words.toAddress(frame.popStackItem());
            final var tbdAddress = frame.getRecipientAddress();
            final var proxyWorldUpdater = (ProxyWorldUpdater) frame.getWorldUpdater();

            // Now proceed with the self-destruct
            final var inheritance =
                    requireNonNull(proxyWorldUpdater.get(tbdAddress)).getBalance();
            final var beneficiary = proxyWorldUpdater.get(beneficiaryAddress);
            final var beneficiaryIsWarm =
                    frame.warmUpAddress(beneficiaryAddress) || gasCalculator().isPrecompile(beneficiaryAddress);
            final var cost = gasCalculator().selfDestructOperationGasCost(beneficiary, inheritance)
                    + (beneficiaryIsWarm ? 0L : gasCalculator().getColdAccountAccessCost());
            if (frame.isStatic()) {
                return new OperationResult(cost, ILLEGAL_STATE_CHANGE);
            } else if (frame.getRemainingGas() < cost) {
                return new OperationResult(cost, INSUFFICIENT_GAS);
            }

            // Enforce Hedera-specific checks on the beneficiary address
            if (addressChecks.isSystemAccount(beneficiaryAddress)
                    || !addressChecks.isPresent(beneficiaryAddress, frame)) {
                return haltFor(null, 0, INVALID_SOLIDITY_ADDRESS);
            }

            // Enforce Hedera-specific restrictions on account deletion
            final var maybeHaltReason =
                    proxyWorldUpdater.tryTrackingSelfDestructBeneficiary(tbdAddress, beneficiaryAddress, frame);
            if (maybeHaltReason.isPresent()) {
                return haltFor(null, 0, maybeHaltReason.get());
            }

            // This will enforce the Hedera signing requirements (while treating any Key{contractID=tbdAddress}
            // or Key{delegatable_contract_id=tbdAddress} keys on the beneficiary account as active); it could
            // also fail if the beneficiary is a token address
            final var maybeReasonToHalt = proxyWorldUpdater.tryTransfer(
                    tbdAddress, beneficiaryAddress, inheritance.toLong(), isDelegateCall(frame));
            if (maybeReasonToHalt.isPresent()) {
                return new OperationResult(cost, maybeReasonToHalt.get());
            }

            // Tell the EVM to delete this contract if pre-Cancun, or, if post-Cancun, only in the
            // same transaction it was created in
            final boolean tellEVMToDoContractDestruct =
                    switch (eip6780Semantics) {
                        case NO -> true;
                        case YES -> frame.wasCreatedInTransaction(tbdAddress);
                    };

            if (tellEVMToDoContractDestruct) {
                frame.addSelfDestruct(tbdAddress);
            }

            frame.addRefund(beneficiaryAddress, inheritance);
            frame.setState(State.CODE_SUCCESS);
            return new OperationResult(cost, null);
        } catch (UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }

    private OperationResult haltFor(
            @Nullable final Account beneficiary, final long inheritance, @NonNull final ExceptionalHaltReason reason) {
        final long cost = gasCalculator().selfDestructOperationGasCost(beneficiary, Wei.of(inheritance));
        return new OperationResult(cost, reason);
    }
}
