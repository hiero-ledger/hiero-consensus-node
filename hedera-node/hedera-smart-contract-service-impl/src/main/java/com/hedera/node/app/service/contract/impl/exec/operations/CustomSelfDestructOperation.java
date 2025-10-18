// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_IS_TREASURY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_STILL_OWNS_NFTS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.operations.CustomizedOpcodes.SELFDESTRUCT;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.isDelegateCall;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import com.hedera.node.app.service.contract.impl.state.AbstractProxyEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.ScheduleEvmAccount;
import com.hedera.node.app.service.contract.impl.state.TokenEvmAccount;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
import java.util.stream.Stream;
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

            // In EVM the SELFDESTRUCT operation never fails.  It always sweeps ETH, and the contract
            // is either deleted or not (per EIP-6780).
            //
            // In Hedera we have to allow for our semantics for transfers.  Notably, we can't
            // transfer hbar unless the signature requirements are met on the transaction, we can't
            // burn hbar, and we don't allow deletion of a contract which is a token treasury.
            // There's also a restriction due to our performance guarantees: We don't allow contracts
            // holding native tokens to self destruct because all the tokens would have to be
            // transferred in the current `handleTransaction` call and if there were too many tokens
            // it would be too expensive (in CPU/memory/database resources) to transfer them all.
            //
            // If the beneficiary account is the contract itself then we have two cases:
            // * If (per EIP-6780) the contract is _not_ going to be deleted: That's ok.  SELFDESTRUCT
            //   is a noop.  But if the contract _is_ going to be deleted and it has a balance of hbar
            //   or any token then SELFDESTRUCT will fail.
            // * Otherwise, if the beneficiary account is _not_ the contract itself then we fail the
            //   SELFDESTRUCT if the contract owns any tokens.

            final boolean contractCreatedInThisTransaction = frame.wasCreatedInTransaction(tbdAddress);
            final boolean contractIsItsOwnBeneficiary = tbdAddress.equals(beneficiaryAddress);
            final boolean contractIsToBeDeleted =
                    switch (eip6780Semantics) {
                        case NO -> true;
                        case YES -> contractCreatedInThisTransaction;
                    };

            // inheritance == amount hbar to sweep
            final var inheritance =
                    requireNonNull(proxyWorldUpdater.get(tbdAddress)).getBalance();
            final var beneficiary = proxyWorldUpdater.get(beneficiaryAddress);
            final var beneficiaryIsWarm =
                    frame.warmUpAddress(beneficiaryAddress) || gasCalculator().isPrecompile(beneficiaryAddress);
            final var coldAccountAccessCost =
                    beneficiaryIsWarm ? 0L : gasCalculator().getColdAccountAccessCost();
            final var costWithoutBeneficiary = getSelfDestructGas(null, Wei.ZERO);
            final var costWithBeneficiary = getSelfDestructGas(beneficiary, inheritance) + coldAccountAccessCost;

            // Initial checks for EVM suitability
            if (frame.isStatic()) {
                return resultFor(costWithBeneficiary, ILLEGAL_STATE_CHANGE);
            }
            if (frame.getRemainingGas() < costWithBeneficiary) {
                return resultFor(costWithBeneficiary, INSUFFICIENT_GAS);
            }

            // Enforce Hedera-specific restrictions on account deletion
            final var maybeReasonToHalt = validateHederaRestrictionsOnBeneficiary(tbdAddress, beneficiaryAddress, frame)
                    .or(() -> validateHederaRestrictionsOnContract(
                            tbdAddress, beneficiaryAddress, frame, contractIsToBeDeleted))
                    .or(() ->
                            proxyWorldUpdater.tryTrackingSelfDestructBeneficiary(tbdAddress, beneficiaryAddress, frame))
                    // Sweeps the hbar from the contract being deleted, if Hedera signing requirements met (while
                    // treating any
                    // Key{contractID=tbdAddress} or Key{delegatable_contract_id=tbdAddress} keys on the beneficiary
                    // account as
                    // active); it could also fail if the beneficiary is a token address.
                    .or(() -> proxyWorldUpdater.tryTransfer(
                            tbdAddress, beneficiaryAddress, inheritance.toLong(), isDelegateCall(frame)));
            if (maybeReasonToHalt.isPresent()) {
                return resultFor(costWithoutBeneficiary, maybeReasonToHalt.get());
            }

            // From this point success is assured ...

            // Frame tracks contracts to be deleted (for handling later)
            if (contractIsToBeDeleted) {
                frame.addSelfDestruct(tbdAddress);
            }

            if (!contractIsItsOwnBeneficiary || contractIsToBeDeleted) {
                proxyWorldUpdater.trackSelfDestructBeneficiary(tbdAddress, beneficiaryAddress, frame);
                // Frame tracks balance changes
                frame.addRefund(beneficiaryAddress, inheritance);
            }

            frame.setState(State.CODE_SUCCESS);
            return resultFor(costWithBeneficiary, null);

        } catch (UnderflowException ignore) {
            return UNDERFLOW_RESPONSE;
        }
    }

    protected @NonNull Optional<ExceptionalHaltReason> validateHederaRestrictionsOnBeneficiary(
            @NonNull final Address deleted, @NonNull final Address beneficiary, @NonNull final MessageFrame frame) {
        requireNonNull(deleted);
        requireNonNull(beneficiary);
        requireNonNull(frame);

        final var proxyWorldUpdater = (ProxyWorldUpdater) frame.getWorldUpdater();
        final var beneficiaryAccount = proxyWorldUpdater.getAccount(beneficiary);

        return Stream.of(
                        // Beneficiary must not be a system account, and ...
                        addressChecks.isSystemAccount(beneficiary),
                        // ... must be present in the frame, and ...
                        !addressChecks.isPresent(beneficiary, frame),
                        // must exist, and ...
                        beneficiaryAccount == null,
                        // ... must not be a token or schedule.
                        beneficiaryAccount instanceof TokenEvmAccount,
                        beneficiaryAccount instanceof ScheduleEvmAccount)
                .filter(Boolean.TRUE::equals)
                .findFirst()
                .flatMap(op -> {
                    FrameUtils.invalidAddressContext(frame)
                            .set(beneficiary, InvalidAddressContext.InvalidAddressType.NonCallTarget);
                    return Optional.of(INVALID_SOLIDITY_ADDRESS);
                });
    }

    protected @NonNull Optional<ExceptionalHaltReason> validateHederaRestrictionsOnContract(
            @NonNull final Address deleted,
            @NonNull final Address beneficiary,
            @NonNull final MessageFrame frame,
            final boolean contractIsToBeDeleted) {
        requireNonNull(deleted);
        requireNonNull(beneficiary);
        requireNonNull(frame);

        final var proxyWorldUpdater = (ProxyWorldUpdater) frame.getWorldUpdater();
        final var deletedAccount = (AbstractProxyEvmAccount) requireNonNull(proxyWorldUpdater.get(deleted));

        // (Contract) account being self-destructed must not be a token treasury
        if (deletedAccount.numTreasuryTitles() > 0) {
            return Optional.of(CONTRACT_IS_TREASURY);
        }

        // Can't sweep native tokens (fungible or non-fungible) from contract being self-destructed
        // Any other situation must sweep, but cannot do that if contract being destructed owns tokens
        // N.B.: Response code name is misleading: Contract can't own fungible tokens either!
        if ((contractIsToBeDeleted || !deleted.equals(beneficiary)) && deletedAccount.numPositiveTokenBalances() > 0) {
            return Optional.of(CONTRACT_STILL_OWNS_NFTS);
        }

        return Optional.empty();
    }

    private @NonNull OperationResult resultFor(final long cost, @Nullable final ExceptionalHaltReason reason) {
        return new OperationResult(cost, reason);
    }

    private long getSelfDestructGas(@Nullable final Account beneficiary, @NonNull final Wei inheritance) {
        return gasCalculator().selfDestructOperationGasCost(beneficiary, inheritance);
    }
}
