// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.util.HapiUtils.CONTRACT_ID_COMPARATOR;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_IS_TREASURY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_STILL_OWNS_NFTS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.FAILURE_DURING_LAZY_ACCOUNT_CREATION;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_ALIAS_KEY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.maybeMissingNumberOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniUInt256;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static java.util.Objects.requireNonNull;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy.UseTopLevelSigs;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.utils.RedirectBytecodeUtils;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * An implementation of {@link EvmFrameState} that uses {@link WritableKVState}s to manage
 * contract storage and bytecode, and a {@link HandleHederaNativeOperations} for additional influence over
 * the non-contract Hedera state in the current scope.
 *
 * <p>Almost every access requires a conversion from a PBJ type to a Besu type. At some
 * point it might be necessary to cache the converted values and invalidate them when
 * the state changes.
 * <p>
 * TODO - get a little further to clarify DI strategy, then bring back a code cache.
 */
public class DispatchingEvmFrameState implements EvmFrameState {
    /**
     * Default value for the key of hollow accounts
     */
    public static final Key HOLLOW_ACCOUNT_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();

    private final HederaNativeOperations nativeOperations;
    private final ContractStateStore contractStateStore;
    private final CodeFactory codeFactory;

    /**
     * @param nativeOperations   the Hedera native operation
     * @param contractStateStore the contract store that manages the key/value states
     * @param codeFactory the
     */
    public DispatchingEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore,
            @NonNull final CodeFactory codeFactory) {
        this.nativeOperations = requireNonNull(nativeOperations);
        this.contractStateStore = requireNonNull(contractStateStore);
        this.codeFactory = codeFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStorageValue(
            @NonNull final ContractID contractID, @NonNull final UInt256 key, @NonNull final UInt256 value) {
        final var slotKey = new SlotKey(contractID, tuweniToPbjBytes(requireNonNull(key)));
        final var oldSlotValue = contractStateStore.getSlotValue(slotKey);
        if (oldSlotValue == null && value.isZero()) {
            // Small optimization---don't put zero into an empty slot
            return;
        }
        // Ensure we don't change any prev/next keys until the base commit
        final var slotValue = new SlotValue(
                tuweniToPbjBytes(requireNonNull(value)),
                oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.previousKey(),
                oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.nextKey());
        // We don't call remove() here when the new value is zero, again because we
        // want to preserve the prev/next key information until the base commit; only
        // then will we remove the zeroed out slot from the K/V state
        contractStateStore.putSlot(slotKey, slotValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull UInt256 getStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        final var slotKey = new SlotKey(contractID, tuweniToPbjBytes(requireNonNull(key)));
        return valueOrZero(contractStateStore.getSlotValue(slotKey));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull UInt256 getOriginalStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        final var slotKey = new SlotKey(contractID, tuweniToPbjBytes(requireNonNull(key)));
        return valueOrZero(contractStateStore.getOriginalSlotValue(slotKey));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull TxStorageUsage getTxStorageUsage(final boolean includeChangedKeys) {
        final Map<ContractID, List<StorageAccess>> modifications = new TreeMap<>(CONTRACT_ID_COMPARATOR);
        final Set<SlotKey> changedKeys = includeChangedKeys ? new HashSet<>() : null;
        contractStateStore.getModifiedSlotKeys().forEach(slotKey -> {
            final var access = StorageAccess.newWrite(
                    pbjToTuweniUInt256(slotKey.key()),
                    valueOrZero(contractStateStore.getOriginalSlotValue(slotKey)),
                    valueOrZero(contractStateStore.getSlotValue(slotKey)));
            modifications
                    .computeIfAbsent(slotKey.contractID(), k -> new ArrayList<>())
                    .add(access);
            if (includeChangedKeys && access.isLogicalChange()) {
                changedKeys.add(slotKey);
            }
        });
        final List<StorageAccesses> allChanges = new ArrayList<>();
        modifications.forEach(
                (number, storageAccesses) -> allChanges.add(new StorageAccesses(number, storageAccesses)));
        return new TxStorageUsage(allChanges, changedKeys);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getKvStateSize() {
        return contractStateStore.getNumSlots();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull RentFactors getRentFactorsFor(final ContractID contractID) {
        final var account = validatedAccount(contractID);
        return new RentFactors(account.contractKvPairsNumber(), account.expirationSecond());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull EntityIdFactory entityIdFactory() {
        return nativeOperations.entityIdFactory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes getCode(@NonNull final ContractID contractID) {
        requireNonNull(contractID);
        final var numberedBytecode = contractStateStore.getBytecode(contractID);
        if (numberedBytecode == null) {
            return Bytes.EMPTY;
        } else {
            final var code = numberedBytecode.code();
            return pbjToTuweniBytes(code);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getCodeHash(@NonNull final ContractID contractID, @NonNull final CodeFactory codeFactory) {
        requireNonNull(contractID);

        final var numberedBytecode = contractStateStore.getBytecode(contractID);
        if (numberedBytecode == null) {
            return Hash.EMPTY;
        } else {
            return codeFactory
                    .createCode(pbjToTuweniBytes(numberedBytecode.code()), false)
                    .getCodeHash();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes getTokenRedirectCode(@NonNull final Address address) {
        return RedirectBytecodeUtils.tokenProxyBytecodeFor(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getTokenRedirectCodeHash(@NonNull final Address address) {
        return codeFactory
                .createCode(RedirectBytecodeUtils.tokenProxyBytecodeFor(address), false)
                .getCodeHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes getAccountRedirectCode(@Nullable final Address address) {
        return RedirectBytecodeUtils.accountProxyBytecodeFor(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getAccountRedirectCodeHash(@Nullable final Address address) {
        return codeFactory
                .createCode(RedirectBytecodeUtils.accountProxyBytecodeFor(address), false)
                .getCodeHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Bytes getScheduleRedirectCode(@Nullable final Address address) {
        return RedirectBytecodeUtils.scheduleProxyBytecodeFor(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getScheduleRedirectCodeHash(@Nullable final Address address) {
        return codeFactory
                .createCode(RedirectBytecodeUtils.scheduleProxyBytecodeFor(address), false)
                .getCodeHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNonce(final AccountID accountID) {
        return validatedAccount(accountID).ethereumNonce();
    }

    @Override
    public com.hedera.hapi.node.state.token.Account getNativeAccount(final AccountID accountID) {
        return validatedAccount(accountID);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumTreasuryTitles(final AccountID accountID) {
        return validatedAccount(accountID).numberTreasuryTitles();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isContract(final AccountID accountID) {
        return validatedAccount(accountID).smartContract();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumPositiveTokenBalances(final AccountID accountID) {
        return validatedAccount(accountID).numberPositiveBalances();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCode(final ContractID contractID, @NonNull final Bytes code) {
        contractStateStore.putBytecode(contractID, new Bytecode(tuweniToPbjBytes(requireNonNull(code))));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNonce(final long number, final long nonce) {
        nativeOperations.setNonce(number, nonce);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Wei getBalance(final AccountID accountID) {
        return Wei.of(validatedAccount(accountID).tinybarBalance());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getIdNumber(@NonNull final Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            throw new IllegalArgumentException("Address " + address + " has no associated Hedera id");
        }
        return number;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Address getAddress(final long number) {
        final AccountID accountID = entityIdFactory().newAccountId(number);
        final var account = nativeOperations.getAccount(accountID);
        if (account != null) {
            if (account.deleted()) {
                return null;
            }

            final var evmAddress = extractEvmAddress(account.alias());
            return evmAddress == null ? asLongZeroAddress(number) : pbjToBesuAddress(evmAddress);
        }
        final var token = nativeOperations.getToken(entityIdFactory().newTokenId(number));
        final var schedule = nativeOperations.getSchedule(entityIdFactory().newScheduleId(number));
        if (token != null || schedule != null) {
            // If the token or schedule  is deleted or expired, the system contract executed by the redirect
            // bytecode will fail with a more meaningful error message, so don't check that here
            return asLongZeroAddress(number);
        }
        throw new IllegalArgumentException("No account, token or schedule has number " + number);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Address getAddress(final AccountID accountID) {
        final var account = nativeOperations.getAccount(accountID);
        if (account == null) {
            throw new IllegalArgumentException("No account has id " + accountID);
        }

        if (account.deleted()) {
            return null;
        }

        final var evmAddress = extractEvmAddress(account.alias());
        return evmAddress == null ? asLongZeroAddress(accountID) : pbjToBesuAddress(evmAddress);
    }

    @Override
    public boolean isHollowAccount(@NonNull final Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return false;
        }
        final AccountID accountID = AccountID.newBuilder().accountNum(number).build();
        final var account = nativeOperations.getAccount(accountID);
        if (account == null) {
            return false;
        }
        return HOLLOW_ACCOUNT_KEY.equals(account.key());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finalizeHollowAccount(@NonNull final Address address) {
        nativeOperations.finalizeHollowAccountAsContract(tuweniToPbjBytes(address));
    }

    @Override
    public long numBytecodesInState() {
        return contractStateStore.getNumBytecodes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryTransfer(
            @NonNull final Address sendingContract,
            @NonNull final Address recipient,
            final long amount,
            final boolean delegateCall) {
        final var from = (AbstractProxyEvmAccount) getAccount(sendingContract);
        if (from == null) {
            return Optional.of(INVALID_SOLIDITY_ADDRESS);
        }
        final var to = getAccount(recipient);
        if (to == null) {
            return Optional.of(INVALID_SOLIDITY_ADDRESS);
        } else if (to instanceof TokenEvmAccount || to instanceof ScheduleEvmAccount) {
            return Optional.of(ILLEGAL_STATE_CHANGE);
        }
        // Note we can still use top-level signatures to meet receiver signature requirements
        final var status = nativeOperations.transferWithReceiverSigCheck(
                amount,
                from.hederaId(),
                ((AbstractProxyEvmAccount) to).hederaId(),
                new ActiveContractVerificationStrategy(
                        from.hederaContractId(),
                        tuweniToPbjBytes(from.getAddress()),
                        delegateCall,
                        UseTopLevelSigs.YES));
        if (status != OK) {
            if (status == INVALID_SIGNATURE) {
                return Optional.of(CustomExceptionalHaltReason.INVALID_SIGNATURE);
            } else {
                throw new IllegalStateException("Transfer from 0.0." + from.accountID
                        + " to 0.0." + ((AbstractProxyEvmAccount) to).accountID
                        + " failed with status " + status + " despite valid preconditions");
            }
        } else {
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryLazyCreation(@NonNull final Address address) {
        if (isLongZero(address)) {
            return Optional.of(INVALID_ALIAS_KEY);
        }
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number != MISSING_ENTITY_NUMBER) {
            final AccountID accountID =
                    AccountID.newBuilder().accountNum(number).build();
            final var account = nativeOperations.getAccount(accountID);
            if (account != null) {
                if (account.expiredAndPendingRemoval()) {
                    return Optional.of(FAILURE_DURING_LAZY_ACCOUNT_CREATION);
                } else {
                    throw new IllegalArgumentException(
                            "Unexpired account 0.0." + number + " already exists at address " + address);
                }
            }
        }
        final var status = nativeOperations.createHollowAccount(tuweniToPbjBytes(address));
        if (status != SUCCESS) {
            return status == MAX_CHILD_RECORDS_EXCEEDED
                    ? Optional.of(INSUFFICIENT_CHILD_RECORDS)
                    : Optional.of(FAILURE_DURING_LAZY_ACCOUNT_CREATION);
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryTrackingSelfDestructBeneficiary(
            @NonNull final Address deleted, @NonNull final Address beneficiary, @NonNull final MessageFrame frame) {
        requireNonNull(deleted);
        requireNonNull(beneficiary);
        requireNonNull(frame);
        if (deleted.equals(beneficiary)) {
            return Optional.of(SELF_DESTRUCT_TO_SELF);
        }
        final var beneficiaryAccount = getAccount(beneficiary);
        if (beneficiaryAccount == null
                || beneficiaryAccount instanceof TokenEvmAccount
                || beneficiaryAccount instanceof ScheduleEvmAccount) {
            return Optional.of(INVALID_SOLIDITY_ADDRESS);
        }
        // Token addresses don't have bytecode that could run a selfdestruct, so this cast is safe
        final var deletedAccount = (AbstractProxyEvmAccount) requireNonNull(getAccount(deleted));
        if (deletedAccount.numTreasuryTitles() > 0) {
            return Optional.of(CONTRACT_IS_TREASURY);
        }
        if (deletedAccount.numPositiveTokenBalances() > 0) {
            return Optional.of(CONTRACT_STILL_OWNS_NFTS);
        }
        nativeOperations.trackSelfDestructBeneficiary(
                deletedAccount.hederaId(), ((AbstractProxyEvmAccount) beneficiaryAccount).hederaId(), frame);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void trackSelfDestructBeneficiary(
            @NonNull final Address deleted, @NonNull final Address beneficiary, @NonNull final MessageFrame frame) {
        requireNonNull(deleted);
        requireNonNull(beneficiary);

        final var beneficiaryAccount = getAccount(beneficiary);
        final var deletedAccount = (AbstractProxyEvmAccount) requireNonNull(getAccount(deleted));

        nativeOperations.trackSelfDestructBeneficiary(
                deletedAccount.hederaId(), ((AbstractProxyEvmAccount) beneficiaryAccount).hederaId(), frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable Account getAccount(@NonNull final Address address) {
        return getMutableAccount(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable MutableAccount getMutableAccount(@NonNull final Address address) {
        final var number = maybeMissingNumberOf(address, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return null;
        }
        final AccountID accountID = entityIdFactory().newAccountId(number);
        final var account = nativeOperations.getAccount(accountID);
        if (account != null) {
            if (account.deleted() || account.expiredAndPendingRemoval() || isNotPriority(address, account)) {
                return null;
            }
            if (account.smartContract()) {
                return new ProxyEvmContract(account.accountId(), this, codeFactory);
            } else {
                return new ProxyEvmAccount(account.accountId(), this);
            }
        }
        final var token = nativeOperations.getToken(entityIdFactory().newTokenId(number));
        if (token != null) {
            // If the token is deleted or expired, the system contract executed by the redirect
            // bytecode will fail with a more meaningful error message, so don't check that here
            return new TokenEvmAccount(address, this);
        }
        final var schedule =
                nativeOperations.getSchedule(nativeOperations.entityIdFactory().newScheduleId(number));
        if (schedule != null) {
            // If the schedule is deleted or expired, the system contract executed by the redirect
            // bytecode will fail with a more meaningful error message, so don't check that here
            return new ScheduleEvmAccount(address, this);
        }
        return null;
    }

    private boolean isNotPriority(
            final Address address, final @NonNull com.hedera.hapi.node.state.token.Account account) {
        requireNonNull(account);
        final var maybeEvmAddress = extractEvmAddress(account.alias());
        return maybeEvmAddress != null && !address.equals(pbjToBesuAddress(maybeEvmAddress));
    }

    private com.hedera.hapi.node.state.token.Account validatedAccount(final AccountID accountID) {
        final var account = nativeOperations.getAccount(accountID);
        if (account == null) {
            throw new IllegalArgumentException("No account has id " + accountID);
        }
        return account;
    }

    private com.hedera.hapi.node.state.token.Account validatedAccount(final ContractID contractID) {
        final var account = nativeOperations.getAccount(contractID);
        if (account == null) {
            throw new IllegalArgumentException("No account found for contract ID " + contractID);
        }
        return account;
    }

    private UInt256 valueOrZero(@Nullable final SlotValue slotValue) {
        return (slotValue == null) ? UInt256.ZERO : pbjToTuweniUInt256(slotValue.value());
    }
}
