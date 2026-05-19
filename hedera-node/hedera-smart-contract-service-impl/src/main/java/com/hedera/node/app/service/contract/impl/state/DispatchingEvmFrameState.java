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
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.keccak256HashOf;
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
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy.UseTopLevelSigs;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
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
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * An implementation of {@link EvmFrameState} that uses {@link WritableKVState}s to manage
 * contract storage and bytecode, and a {@link HandleHederaNativeOperations} for additional influence over
 * the non-contract Hedera state in the current scope.
 *
 * <p>Each instance is scoped to a single Besu world-updater frame (one is created per call to
 * {@link ScopedEvmFrameStateFactory#get()} from {@link ProxyWorldUpdater}, including child updaters).
 * Per-frame caches are maintained to avoid repeatedly allocating PBJ {@link SlotKey} flyweights and
 * re-doing PBJ&nbsp;&rarr;&nbsp;Tuweni conversions on the SLOAD/SSTORE/SLOAD-original hot path:
 *
 * <ul>
 *   <li>{@code slotKeyCache} interns {@link SlotKey} instances by primitive {@code (contractNum, key)}
 *       so a re-access of the same slot reuses the same {@link SlotKey} reference (collapses
 *       {@link SlotKey#equals(Object)} to identity inside the {@link WritableKVState} bucket lookup).</li>
 *   <li>{@code originalValueCache} memoises {@link #getOriginalStorageValue} results; original values
 *       are invariant for the life of the transaction so this cache is never invalidated within the
 *       frame.</li>
 *   <li>{@code liveValueCache} memoises {@link #getStorageValue} results; refreshed in-place by
 *       {@link #setStorageValue} and cleared by {@link #invalidateReadCaches()} when a child frame
 *       commits writes through the underlying state.</li>
 *   <li>{@code codeCache} memorises bytecode and code hashes per {@link ContractID}; Tuweni bytecode
 *       is populated lazily when {@link #getCode(ContractID)} is called after a hash-only lookup.</li>
 *   <li>{@code delegationCodeHashCache} memorises EIP-7702 delegation indicator hashes per
 *       {@link AccountID}.</li>
 * </ul>
 */
public class DispatchingEvmFrameState implements EvmFrameState {
    /**
     * Per-contract bytecode and code-hash cache entry for the current frame.
     *
     * @param pbjCode {@code null} when no bytecode exists in state; otherwise the PBJ bytecode
     * @param tuweniCode lazily populated Tuweni bytecode; {@code null} until {@link #getCode(ContractID)} needs it
     * @param hash the code hash for this contract's bytecode in state
     */
    private record CodeCacheEntry(
            @Nullable com.hedera.pbj.runtime.io.buffer.Bytes pbjCode,
            @Nullable Bytes tuweniCode,
            @NonNull Hash hash) {}
    /**
     * Default value for the key of hollow accounts
     */
    public static final Key HOLLOW_ACCOUNT_KEY =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();

    private static final int INITIAL_CACHE_CAPACITY = 64;

    private final HederaNativeOperations nativeOperations;
    private final HederaEntityResolver hederaEntityResolver;
    final ContractStateStore contractStateStore;

    /**
     * Shared mutable probe used for {@link ConcurrentHashMap#get(Object)} lookups against {@link #slotKeyCache},
     * {@link #originalValueCache}, and {@link #liveValueCache}. On a cache miss callers must store an
     * immutable {@link SlotLookupKey#copy()} as the map key.
     */
    private final SlotLookupKey probeKey = new SlotLookupKey();

    private final Map<SlotLookupKey, SlotKey> slotKeyCache =
            new ConcurrentHashMap<>(INITIAL_CACHE_CAPACITY);
    private final Map<SlotLookupKey, UInt256> originalValueCache =
            new ConcurrentHashMap<>(INITIAL_CACHE_CAPACITY);
    private final Map<SlotLookupKey, UInt256> liveValueCache =
            new ConcurrentHashMap<>(INITIAL_CACHE_CAPACITY);
    private final Map<ContractID, CodeCacheEntry> codeCache =
            new ConcurrentHashMap<>(INITIAL_CACHE_CAPACITY);
    private final Map<AccountID, Hash> delegationCodeHashCache =
            new ConcurrentHashMap<>(INITIAL_CACHE_CAPACITY);

    /**
     * @param nativeOperations   the Hedera native operation
     * @param contractStateStore the contract store that manages the key/value states
     */
    public DispatchingEvmFrameState(
            @NonNull final HederaNativeOperations nativeOperations,
            @NonNull final ContractStateStore contractStateStore) {
        this.nativeOperations = requireNonNull(nativeOperations);
        this.hederaEntityResolver = new HederaEntityResolver(nativeOperations);
        this.contractStateStore = requireNonNull(contractStateStore);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setStorageValue(
            @NonNull final ContractID contractID, @NonNull final UInt256 key, @NonNull final UInt256 value) {
        requireNonNull(contractID);
        requireNonNull(key);
        requireNonNull(value);

        final var slotKey = internedSlotKey(contractID, key);
        final var oldSlotValue = contractStateStore.getSlotValue(slotKey);
        if (oldSlotValue == null && value.isZero()) {
            // Small optimization---don't put zero into an empty slot
            return;
        }
        // Ensure we don't change any prev/next keys until the base commit
        final var slotValue = new SlotValue(
                tuweniToPbjBytes(value),
                oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.previousKey(),
                oldSlotValue == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : oldSlotValue.nextKey());
        // We don't call remove() here when the new value is zero, again because we
        // want to preserve the prev/next key information until the base commit; only
        // then will we remove the zeroed out slot from the K/V state
        contractStateStore.putSlot(slotKey, slotValue);
        // Refresh the live value cache so a subsequent SLOAD of this slot in the same
        // frame doesn't have to round-trip through the K/V state again
        if (contractID.hasContractNum()) {
            setProbe(contractID.contractNumOrThrow(), key);
            liveValueCache.put(probeKey.copy(), value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull UInt256 getStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        requireNonNull(contractID);
        requireNonNull(key);
        if (!contractID.hasContractNum()) {
            // Aliased ContractIDs do not appear on the storage hot path; fall back to the
            // original allocation-heavy code path to preserve behaviour exactly
            final var slotKey = new SlotKey(contractID, tuweniToPbjBytes(key));
            return valueOrZero(contractStateStore.getSlotValue(slotKey));
        }
        setProbe(contractID.contractNumOrThrow(), key);
        final var cached = liveValueCache.get(probeKey);
        if (cached != null) {
            return cached;
        }
        final var slotKey = internedSlotKey(contractID, key);
        final var value = valueOrZero(contractStateStore.getSlotValue(slotKey));
        liveValueCache.put(probeKey.copy(), value);
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull UInt256 getOriginalStorageValue(final ContractID contractID, @NonNull final UInt256 key) {
        requireNonNull(contractID);
        requireNonNull(key);
        if (!contractID.hasContractNum()) {
            final var slotKey = new SlotKey(contractID, tuweniToPbjBytes(key));
            return valueOrZero(contractStateStore.getOriginalSlotValue(slotKey));
        }
        setProbe(contractID.contractNumOrThrow(), key);
        final var cached = originalValueCache.get(probeKey);
        if (cached != null) {
            return cached;
        }
        final var slotKey = internedSlotKey(contractID, key);
        final var value = valueOrZero(contractStateStore.getOriginalSlotValue(slotKey));
        originalValueCache.put(probeKey.copy(), value);
        return value;
    }

    /**
     * Returns an interned {@link SlotKey} for {@code (contractID, key)}, allocating one only on the
     * first miss for a given slot inside this frame. Caller must ensure {@code contractID} has a
     * {@code contractNum} (i.e. {@link ContractID#hasContractNum()} returns true) and that
     * {@link #setProbe(long, UInt256)} has not yet been called for the same lookup, since this
     * method itself updates the shared probe.
     */
    private @NonNull SlotKey internedSlotKey(@NonNull final ContractID contractID, @NonNull final UInt256 key) {
        if (!contractID.hasContractNum()) {
            return new SlotKey(contractID, tuweniToPbjBytes(key));
        }
        setProbe(contractID.contractNumOrThrow(), key);
        var hit = slotKeyCache.get(probeKey);
        if (hit != null) {
            return hit;
        }
        final var fresh = new SlotKey(contractID, tuweniToPbjBytes(key));
        slotKeyCache.put(probeKey.copy(), fresh);
        return fresh;
    }

    private void setProbe(final long contractNum, @NonNull final UInt256 key) {
        probeKey.set(contractNum, key.getLong(0), key.getLong(8), key.getLong(16), key.getLong(24));
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
                    .computeIfAbsent(slotKey.contractID(), _ -> new ArrayList<>())
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
    public @NonNull RentFactors getRentFactorsFor(@NonNull final ContractID contractID) {
        final var account = validatedAccount(contractID);
        return new RentFactors(account.contractKvPairsNumber(), account.expirationSecond());
    }

    @Override
    public @NonNull RentFactors getRentFactorsFor(@NonNull final AccountID accountId) {
        final var account = validatedAccount(accountId);
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
        final var cached = codeCache.get(contractID);
        if (cached != null) {
            if (cached.tuweniCode() != null) {
                return cached.tuweniCode();
            }
            final var tuweniCode = tuweniCodeFromPbj(cached.pbjCode());
            codeCache.put(contractID, new CodeCacheEntry(cached.pbjCode(), tuweniCode, cached.hash()));
            return tuweniCode;
        }
        final var numberedBytecode = contractStateStore.getBytecode(contractID);
        if (numberedBytecode == null) {
            final var entry = new CodeCacheEntry(null, Bytes.EMPTY, Hash.EMPTY);
            codeCache.put(contractID, entry);
            return Bytes.EMPTY;
        }
        final var pbjCode = numberedBytecode.code();
        final var tuweniCode = tuweniCodeFromPbj(pbjCode);
        final var hash = keccak256HashOf(pbjCode == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : pbjCode);
        codeCache.put(contractID, new CodeCacheEntry(pbjCode, tuweniCode, hash));
        return tuweniCode;
    }

    /**
     *  Return PBJ bytes to avoid a copy
     */
    public com.hedera.pbj.runtime.io.buffer.Bytes getCodePBJ(ContractID contractID) {
        return contractStateStore.getBytecode(contractID).code();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Hash getCodeHash(@NonNull final ContractID contractID) {
        requireNonNull(contractID);
        final var cached = codeCache.get(contractID);
        if (cached != null) {
            return cached.hash();
        }
        final var numberedBytecode = contractStateStore.getBytecode(contractID);
        if (numberedBytecode == null) {
            codeCache.put(contractID, new CodeCacheEntry(null, null, Hash.EMPTY));
            return Hash.EMPTY;
        }
        final var pbjCode = numberedBytecode.code();
        final var safePbjCode = pbjCode == null ? com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY : pbjCode;
        final var hash = keccak256HashOf(safePbjCode);
        codeCache.put(contractID, new CodeCacheEntry(pbjCode, null, hash));
        return hash;
    }

    /**
     * Returns the code hash for an EIP-7702 delegation indicator account, cached for the life of this frame.
     *
     * @param accountId the Hedera account id
     * @param delegationAddress the delegation target address bytes from account state
     * @return the code hash
     */
    public @NonNull Hash getDelegationCodeHash(
            @NonNull final AccountID accountId,
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes delegationAddress) {
        requireNonNull(accountId);
        requireNonNull(delegationAddress);
        if (delegationAddress.length() == 0) {
            return Code.EMPTY_CODE.getCodeHash();
        }
        return delegationCodeHashCache.computeIfAbsent(
                accountId, _ -> keccak256HashOf(ProxyEvmAccount.createDelegationIndicatorPJB(delegationAddress)));
    }

    private static @NonNull Bytes tuweniCodeFromPbj(
            @Nullable final com.hedera.pbj.runtime.io.buffer.Bytes pbjCode) {
        return pbjCode == null ? Bytes.EMPTY : pbjToTuweniBytes(pbjCode);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNonce(final AccountID accountID) {
        return validatedAccount(accountID).ethereumNonce();
    }

    @Override
    public Account getNativeAccount(final AccountID accountID) {
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
        requireNonNull(contractID);
        requireNonNull(code);
        final var pbjCode = tuweniToPbjBytes(code);
        contractStateStore.putBytecode(contractID, new Bytecode(pbjCode));
        // Keep the per-frame code cache consistent with the freshly written bytecode;
        // otherwise a subsequent getCode/getCodeHash in this frame would return a stale
        // value cached during contract creation, breaking e.g. bytecode-sidecar externalization.
        final var hash = code.isEmpty() ? Code.EMPTY_CODE.getCodeHash() : Hash.hash(code);
        codeCache.put(contractID, new CodeCacheEntry(pbjCode, code, hash));
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
        nativeOperations.finalizeHollowAccountAsContract(tuweniToPbjBytes(address.getBytes()));
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
                to.hederaId(),
                new ActiveContractVerificationStrategy(
                        from.hederaContractId(),
                        tuweniToPbjBytes(from.getAddress().getBytes()),
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
        final var maybeValidationError = validateAccountCreation(address);
        if (maybeValidationError.isPresent()) {
            return maybeValidationError;
        }
        final var status = nativeOperations.createHollowAccount(tuweniToPbjBytes(address.getBytes()));
        return accountCreationStatusToResult(status);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ExceptionalHaltReason> tryCreateAccountWithKeyAndCodeDelegation(
            @NonNull final Address address, @NonNull byte[] ecdsaPublicKey, @NonNull Address delegationAddress) {
        final var maybeValidationError = validateAccountCreation(address);
        if (maybeValidationError.isPresent()) {
            return maybeValidationError;
        }
        final var status = nativeOperations.createAccountWithKeyAndCodeDelegation(
                tuweniToPbjBytes(address.getBytes()),
                Key.newBuilder()
                        .ecdsaSecp256k1(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(ecdsaPublicKey))
                        .build(),
                tuweniToPbjBytes(delegationAddress.getBytes()));
        return accountCreationStatusToResult(status);
    }

    private Optional<ExceptionalHaltReason> validateAccountCreation(@NonNull final Address address) {
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
        return Optional.empty();
    }

    private Optional<ExceptionalHaltReason> accountCreationStatusToResult(ResponseCodeEnum status) {
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
        nativeOperations.trackSelfDestructBeneficiary(deletedAccount.hederaId(), beneficiaryAccount.hederaId(), frame);
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

        nativeOperations.trackSelfDestructBeneficiary(deletedAccount.hederaId(), beneficiaryAccount.hederaId(), frame);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable AbstractMutableEvmAccount getAccount(@NonNull Address address) {
        return getMutableAccount(address);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable AbstractMutableEvmAccount getMutableAccount(@NonNull Address address) {
        final var maybeEntity = hederaEntityResolver.resolveEvmAddressToHederaEntity(address);
        if (maybeEntity == null) {
            return null;
        }
        return switch (maybeEntity) {
            case HederaEntityResolver.HederaEntity.AccountEntity(Account account) ->
                account.smartContract()
                        ? new ProxyEvmContract(account.accountId(), this)
                        : new ProxyEvmAccount(account, this);
            case HederaEntityResolver.HederaEntity.TokenEntity(Token token) ->
                // If the token is deleted or expired, the system contract executed by the redirect
                // bytecode will fail with a more meaningful error message, so don't check that here
                new TokenEvmAccount(address, this);
            case HederaEntityResolver.HederaEntity.ScheduleEntity(Schedule schedule) ->
                // If the schedule is deleted or expired, the system contract executed by the redirect
                // bytecode will fail with a more meaningful error message, so don't check that here
                new ScheduleEvmAccount(address, this);
        };
    }

    private Account validatedAccount(final AccountID accountID) {
        final var account = nativeOperations.getAccount(accountID);
        if (account == null) {
            throw new IllegalArgumentException("No account has id " + accountID);
        }
        return account;
    }

    private Account validatedAccount(final ContractID contractID) {
        final var account = nativeOperations.getAccount(contractID);
        if (account == null) {
            throw new IllegalArgumentException("No account found for contract ID " + contractID);
        }
        return account;
    }

    protected UInt256 valueOrZero(@Nullable final SlotValue slotValue) {
        return (slotValue == null) ? UInt256.ZERO : pbjToTuweniUInt256(slotValue.value());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Clears caches whose entries can be invalidated by a child frame committing writes through
     * the underlying {@link ContractStateStore}: the live slot-value cache, the code cache, and the
     * delegation code-hash cache. The {@link SlotKey} flyweight cache and the original-value cache
     * are content-addressed and tx-stable respectively, so they are kept.
     */
    @Override
    public void invalidateReadCaches() {
        liveValueCache.clear();
        codeCache.clear();
        delegationCodeHashCache.clear();
    }
}
