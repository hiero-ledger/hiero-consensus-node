// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.aliasFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumberedContractId;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.spi.workflows.ResourceExhaustedException.validateResource;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

/**
 * A {@link WorldUpdater} that delegates to a given {@link HandleHederaOperations} for state management.
 *
 * <p>For convenience, creates a {@link EvmFrameState} to manage the contract storage and
 * bytecode in the EVM frame via Besu types. <b>Important:</b> however, the {@link EvmFrameState}
 * does not itself provide any transactional semantics. The {@link HandleHederaOperations} alone has the
 * responsibility to {@code commit()} and {@code revert()} changes across all forms of
 * state as a transaction unit.
 *
 * <p><i>Note:</i> The {@code sbhRefund} field in the {@code mono-service} {@link WorldUpdater}
 * hierarchy is---as best I can tell---now always zero. So it does not appear here.
 */
public class ProxyWorldUpdater implements HederaWorldUpdater {
    private static final String CANNOT_CREATE = "Cannot create ";

    /**
     * The factory used to create new {@link EvmFrameState} instances; used once in the
     * constructor, and then again in {@link #updater()} if that is called.
     */
    private final EvmFrameStateFactory evmFrameStateFactory;
    /**
     * The parent {@link WorldUpdater}, or null if this is the root updater.
     */
    @Nullable
    private final WorldUpdater parent;

    /**
     * The {@link EvmFrameState} managing this {@code ProxyWorldUpdater}'s state.
     */
    protected final EvmFrameState evmFrameState;

    /**
     * Enhancements needed by Hedera EVM customizations and system contracts.
     */
    protected final Enhancement enhancement;

    /**
     * If our {@code CreateOperation}s used the addresses prescribed by the {@code CREATE} and
     * {@code CREATE2} specs, they would not need Hedera state and thus not need to call into
     * their frame's {@link ProxyWorldUpdater}. Similarly, if a {@link ProxyWorldUpdater}
     * did not need any frame context to create a new account, it would not need any extra
     * "setup" help the {@code CreateOperation}.
     *
     * <p>However,
     * <ul>
     *     <li>The {@code CreateOperation} needs to call into the {@link ProxyWorldUpdater}
     *     because our {@code CREATE} address derives from the next Hedera entity number.</li>
     *     <li>To correctly create an account, the {@link ProxyWorldUpdater} must know the
     *     recipient address of the parent frame, as any children created in this frame
     *     will "inherit" many of their Hedera properties from the recipient.</li>
     * </ul>
     *
     * <p>So we need a little scratchpad to facilitate this data exchange with any create
     * operations executing in this {@link ProxyWorldUpdater}'s frame.
     */
    @Nullable
    protected PendingCreation pendingCreation;

    protected boolean reverted = false;

    protected boolean contractMustBePresent = true;

    public ProxyWorldUpdater(
            @NonNull final Enhancement enhancement,
            @NonNull final EvmFrameStateFactory evmFrameStateFactory,
            @Nullable final WorldUpdater parent) {
        this.parent = parent;
        this.enhancement = requireNonNull(enhancement);
        this.evmFrameStateFactory = requireNonNull(evmFrameStateFactory);
        this.evmFrameState = evmFrameStateFactory.get();
    }

    /**
     * Returns the pending creation, if any, for this updater.
     *
     * @return the pending creation, if any, for this updater
     */
    public @Nullable PendingCreation getPendingCreation() {
        return pendingCreation;
    }

    @Override
    public @NonNull Enhancement enhancement() {
        return enhancement;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable HederaEvmAccount getHederaAccount(@NonNull AccountID accountId) {
        final Address address;
        if (accountId.hasAlias()) {
            address = pbjToBesuAddress(accountId.aliasOrThrow());
        } else {
            try {
                address = evmFrameState.getAddress(accountId);
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return address == null ? null : (HederaEvmAccount) get(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ContractID getHederaContractId(@NonNull final Address address) {
        requireNonNull(address);
        final var account = (HederaEvmAccount) get(address);
        if (account == null) {
            // Also return ids for pending creations
            if (pendingCreation != null && pendingCreation.address().equals(address)) {
                return entityIdFactory().newContractId(pendingCreation.number());
            } else {
                if (!contractMustBePresent) {
                    return isLongZero(address)
                            ? asNumberedContractId(entityIdFactory(), address)
                            : asEvmContractId(entityIdFactory(), address);
                }
                throw new IllegalArgumentException("No contract pending or extant at " + address);
            }
        }
        return account.hederaContractId();
    }

    @Override
    public @NonNull Bytes entropy() {
        return pbjToTuweniBytes(enhancement.operations().entropy());
    }

    @Override
    public @Nullable HederaEvmAccount getHederaAccount(@NonNull ContractID contractId) {
        requireNonNull(contractId);
        contractId = enhancement.operations().shardAndRealmValidated(contractId);
        final Address address;
        if (contractId.hasEvmAddress()) {
            address = pbjToBesuAddress(contractId.evmAddressOrThrow());
        } else {
            try {
                address = evmFrameState.getAddress(contractId.contractNumOrElse(0L));
            } catch (IllegalArgumentException ignore) {
                return null;
            }
        }
        return address == null ? null : (HederaEvmAccount) get(address);
    }

    @Override
    public void collectGasFee(@NonNull final AccountID payerId, final long amount, final boolean withNonceIncrement) {
        requireNonNull(payerId);
        enhancement.operations().collectGasFee(payerId, amount, withNonceIncrement);
    }

    @Override
    public void refundGasFee(@NonNull final AccountID payerId, final long amount) {
        requireNonNull(payerId);
        enhancement.operations().refundGasFee(payerId, amount);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryTransfer(
            @NonNull final Address from, @NonNull final Address to, final long amount, final boolean delegateCall) {
        return evmFrameState.tryTransfer(from, to, amount, delegateCall);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryLazyCreation(
            @NonNull final Address recipient, @NonNull final MessageFrame frame) {
        final var gasCost = enhancement.operations().lazyCreationCostInGas(recipient);
        if (gasCost > frame.getRemainingGas()) {
            return Optional.of(INSUFFICIENT_GAS);
        }
        final var maybeHaltReason = evmFrameState.tryLazyCreation(recipient);
        if (maybeHaltReason.isPresent()) {
            return maybeHaltReason;
        }
        frame.decrementRemainingGas(gasCost);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHollowAccount(@NonNull final Address address) {
        return evmFrameState.isHollowAccount(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address setupTopLevelCreate(@NonNull ContractCreateTransactionBody body) {
        setupPendingCreation(null, requireNonNull(body), null);
        return requireNonNull(pendingCreation).address();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupTopLevelLazyCreate(@NonNull final Address alias) {
        setupPendingCreation(null, null, requireNonNull(alias));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupAliasedTopLevelCreate(@NonNull ContractCreateTransactionBody body, @NonNull Address alias) {
        setupPendingCreation(null, requireNonNull(body), requireNonNull(alias));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Address setupInternalCreate(@NonNull final Address origin) {
        setupPendingCreation(origin, null, null);
        return requireNonNull(pendingCreation).address();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setupInternalAliasedCreate(@NonNull final Address origin, @NonNull final Address alias) {
        setupPendingCreation(origin, null, requireNonNull(alias));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finalizeHollowAccount(@NonNull final Address address, @NonNull final Address parent) {
        evmFrameState.finalizeHollowAccount(address);
        // Reset pending creation to null, as a CREATE2 operation "collided" with an existing
        // hollow account instead of creating a truly new contract
        pendingCreation = null;
        enhancement.operations().externalizeHollowAccountMerge(getHederaContractId(address), aliasFrom(address));
    }

    @Override
    public @NonNull List<StorageAccesses> pendingStorageUpdates() {
        return evmFrameState.getStorageChanges();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryTrackingSelfDestructBeneficiary(
            @NonNull final Address deleted, @NonNull final Address beneficiary, @NonNull final MessageFrame frame) {
        return evmFrameState.tryTrackingSelfDestructBeneficiary(deleted, beneficiary, frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void trackSelfDestructBeneficiary(
            @NonNull final Address deleted, @NonNull final Address beneficiary, @NonNull final MessageFrame frame) {
        evmFrameState.trackSelfDestructBeneficiary(deleted, beneficiary, frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Account get(@NonNull final Address address) {
        return evmFrameState.getAccount(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MutableAccount getAccount(@NonNull final Address address) {
        return evmFrameState.getMutableAccount(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MutableAccount createAccount(@NonNull final Address address, final long nonce, @NonNull final Wei balance) {
        if (pendingCreation == null) {
            throw new IllegalStateException(CANNOT_CREATE + address + " without a pending creation");
        }
        // Enforce the contracts and accounts creation limit here, since contracts are accounts
        final var newEntityCount = evmFrameState.numBytecodesInState() + 1;
        if (newEntityCount > enhancement.operations().contractCreationLimit()
                || newEntityCount > enhancement.operations().accountCreationLimit()) {
            throw new ResourceExhaustedException(MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
        }
        final var number = getValidatedCreationNumber(address, balance, pendingCreation);
        if (pendingCreation.isHapiCreation()) {
            enhancement
                    .operations()
                    .createContract(
                            number, requireNonNull(pendingCreation.body()), pendingCreation.aliasIfApplicable());
        } else {
            enhancement
                    .operations()
                    .createContract(number, pendingCreation.parentNumber(), pendingCreation.aliasIfApplicable());
        }
        return evmFrameState.getMutableAccount(pendingCreation.address());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteAccount(@NonNull final Address address) {
        if (isLongZero(address)) {
            enhancement.operations().deleteUnaliasedContract(numberOfLongZero(address));
        } else {
            enhancement.operations().deleteAliasedContract(aliasFrom(address));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revert() {
        // It might seem like we should have a call to evmFrameState.revert() here; but remember the
        // EvmFrameState is just a convenience wrapper around the scope to let us use Besu types, and
        // ultimately the HederaOperations is the one tracking and managing all changes
        enhancement.operations().revert();
        // Because of the revert-then-commit pattern that Besu uses for force deletions in
        // AbstractMessageProcessor#clearAccumulatedStateBesidesGasAndOutput(), we have
        // to take special measures here to avoid popping the savepoint stack twice for
        // this frame
        reverted = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContractNotRequired() {
        contractMustBePresent = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("java:S125")
    public void commit() {
        // It might seem like we should have a call to evmFrameState.commit() here; but remember the
        // EvmFrameState is just a convenience wrapper around the scope to let us use Besu types, and
        // ultimately the HederaOperations is the one tracking and managing all changes
        if (!reverted) {
            enhancement.operations().commit();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Optional<WorldUpdater> parentUpdater() {
        return Optional.ofNullable(parent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ProxyWorldUpdater updater() {
        enhancement.operations().begin();
        final var child = new ProxyWorldUpdater(enhancement, evmFrameStateFactory, this);
        // Hand off any pending creation to the child updater; this a bit of a hack, but
        // lets the TransactionProcessor client code "flow" as naturally as possible,
        // without need to defer setting up creation until the initial frame is built
        // (since its updater will be a child of the RootProxyWorldUpdater)
        if (this.pendingCreation != null) {
            child.pendingCreation = this.pendingCreation;
        }
        child.contractMustBePresent = this.contractMustBePresent;
        return child;
    }

    /**
     * Returns the accounts that have been touched (i.e., created or maybe mutated but <i>not</i> deleted)
     * within the scope of this updater.
     *
     * <p>We may not actually need this, as Besu only uses it in
     * {@code AbstractMessageProcessor.clearAccumulatedStateBesidesGasAndOutput()}, which seems to deal
     * with side effects of an Ethereum consensus bug.
     *
     * @return the accounts that have been touched
     */
    @Override
    public @NonNull Collection<? extends Account> getTouchedAccounts() {
        final var modifiedNumbers = enhancement.operations().getModifiedAccountNumbers();
        final List<Account> touched = new ArrayList<>();
        for (final var number : modifiedNumbers) {
            // Returns null if the account has been deleted
            final var address = evmFrameState.getAddress(number);
            if (address != null) {
                touched.add(evmFrameState.getAccount(address));
            }
        }
        return touched;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Collection<Address> getDeletedAccountAddresses() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public ExchangeRate currentExchangeRate() {
        return enhancement().systemOperations().currentExchangeRate();
    }

    private long getValidatedCreationNumber(
            @NonNull final Address address,
            @NonNull final Wei balance,
            @NonNull final PendingCreation knownPendingCreation) {
        if (!balance.isZero()) {
            throw new IllegalStateException(CANNOT_CREATE + address + " with non-zero balance " + balance);
        }
        final var pendingAddress = knownPendingCreation.address();
        if (!requireNonNull(address).equals(pendingAddress)) {
            throw new IllegalStateException(CANNOT_CREATE + address + " with " + pendingAddress + " pending");
        }
        final var pendingNumber = enhancement.operations().peekNextEntityNumber();
        if (pendingNumber != knownPendingCreation.number()) {
            throw new IllegalStateException(CANNOT_CREATE + address + " with number " + pendingNumber + " ("
                    + knownPendingCreation.number() + ") pending");
        }
        return pendingNumber;
    }

    private void setupPendingCreation(
            @Nullable final Address origin,
            @Nullable final ContractCreateTransactionBody body,
            @Nullable final Address alias) {
        final var number = enhancement.operations().peekNextEntityNumber();
        pendingCreation = new PendingCreation(
                alias == null ? asLongZeroAddress(number) : alias,
                number,
                origin != null ? evmFrameState.getIdNumber(origin) : MISSING_ENTITY_NUMBER,
                body);
    }

    public void checkOpsDurationThrottle(final long currentOpsDuration) {
        final var throttleAdviser = enhancement.operations().getThrottleAdviser();
        if (throttleAdviser != null) {
            validateResource(!throttleAdviser.shouldThrottleByOpsDuration(currentOpsDuration), THROTTLED_AT_CONSENSUS);
        }
    }
}
