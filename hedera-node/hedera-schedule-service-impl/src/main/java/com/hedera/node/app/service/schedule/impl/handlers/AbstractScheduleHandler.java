// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_PENDING_EXPIRATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.childAsOrdinary;
import static com.hedera.node.app.spi.workflows.DispatchOptions.subDispatch;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ContractID.ContractOneOfType;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.KeyOneOfType;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.keys.KeyComparator;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.ScheduleStreamBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.DispatchOptions.PropagateFeeChargingStrategy;
import com.hedera.node.app.spi.workflows.DispatchOptions.StakingRewards;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.TransactionKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.Predicate;

/**
 * Provides some implementation support needed for both the {@link ScheduleCreateHandler} and {@link
 * ScheduleSignHandler}.
 */
public abstract class AbstractScheduleHandler {
    static final Comparator<Key> KEY_COMPARATOR = new KeyComparator();

    private final FeeCharging customFeeCharging;

    protected AbstractScheduleHandler(@NonNull final FeeCharging customFeeCharging) {
        this.customFeeCharging = requireNonNull(customFeeCharging);
    }

    @FunctionalInterface
    protected interface TransactionKeysFn {
        TransactionKeys apply(@NonNull TransactionBody body, @NonNull AccountID payerId) throws PreCheckException;
    }

    /**
     * Gets the {@link TransactionKeys} summarizing a schedule's signing requirements.
     *
     * @param schedule the schedule
     * @param fn the function to get required keys by category
     * @return the schedule's signing requirements
     * @throws HandleException if the signing requirements cannot be determined
     */
    protected @NonNull TransactionKeys getTransactionKeysOrThrow(
            @NonNull final Schedule schedule, @NonNull final TransactionKeysFn fn) throws HandleException {
        requireNonNull(schedule);
        requireNonNull(fn);
        try {
            return getRequiredKeys(schedule, fn);
        } catch (final PreCheckException e) {
            throw new HandleException(e.responseCode());
        }
    }

    /**
     * Gets the {@link TransactionKeys} summarizing a schedule's signing requirements.
     *
     * @param schedule the schedule
     * @param fn the function to get required keys by category
     * @return the schedule's signing requirements
     * @throws PreCheckException if the signing requirements cannot be determined
     */
    @NonNull
    protected TransactionKeys getRequiredKeys(@NonNull final Schedule schedule, @NonNull final TransactionKeysFn fn)
            throws PreCheckException {
        requireNonNull(schedule);
        requireNonNull(fn);
        final var body = childAsOrdinary(schedule);
        final var creatorId = schedule.originalCreateTransactionOrThrow()
                .transactionIDOrThrow()
                .accountIDOrThrow();
        final var payerId = schedule.payerAccountIdOrElse(creatorId);
        final var transactionKeys = fn.apply(body, payerId);
        // We do not currently support scheduling transactions that would need to complete hollow accounts
        if (!transactionKeys.requiredHollowAccounts().isEmpty()) {
            throw new PreCheckException(UNRESOLVABLE_REQUIRED_SIGNERS);
        }
        return transactionKeys;
    }

    /**
     * Gets all required keys for a transaction, including the payer key and all non-payer keys.
     *
     * @param keys the transaction keys
     * @return the required keys
     */
    protected @NonNull List<Key> allRequiredKeys(@NonNull final TransactionKeys keys) {
        final var all = new ArrayList<Key>();
        all.add(keys.payerKey());
        all.addAll(keys.requiredNonPayerKeys());
        return all;
    }

    /**
     * Given a set of signing simple keys, a list of signatories, and a list of required keys (possibly complex),
     * returns a new list of signatories that includes all the original signatories as well as,
     * <ol>
     *     <li>Any crypto keys that are both constituents of the required keys and in the authorizing keys set.</li>
     *     <li>Any keys of type {@link KeyOneOfType#CONTRACT_ID} using {@link ContractOneOfType#CONTRACT_NUM}.</li>
     * </ol>
     *
     * @param authorizingKeys the authorizing simple keys
     * @param signatories the original signatories
     * @param requiredKeys the required keys
     * @return the new signatories
     */
    protected static @NonNull List<Key> newSignatories(
            @NonNull final SortedSet<Key> authorizingKeys,
            @NonNull final List<Key> signatories,
            @NonNull final List<Key> requiredKeys) {
        requireNonNull(authorizingKeys);
        requireNonNull(signatories);
        requireNonNull(requiredKeys);
        final var newSignatories = new ConcurrentSkipListSet<>(KEY_COMPARATOR);
        newSignatories.addAll(signatories);
        requiredKeys.forEach(k -> accumulateNewSignatories(newSignatories, authorizingKeys, k));
        authorizingKeys.forEach(key -> {
            if (isNumericContractIdKey(key)) {
                newSignatories.add(key);
            }
        });
        return new ArrayList<>(newSignatories);
    }

    /**
     * Either returns a schedule from the given store with the given id, ready to be modified, or throws a
     * {@link PreCheckException} if the schedule is not found or is not in a valid state.
     *
     * @param scheduleId the schedule to get and validate
     * @param scheduleStore the schedule store
     * @throws PreCheckException if the schedule is not found or is not in a valid state
     */
    @NonNull
    protected Schedule getValidated(
            @NonNull final ScheduleID scheduleId,
            @NonNull final ReadableScheduleStore scheduleStore,
            final boolean isLongTermEnabled)
            throws PreCheckException {
        requireNonNull(scheduleId);
        requireNonNull(scheduleStore);
        final var schedule = scheduleStore.get(scheduleId);
        final var validationResult = validate(schedule, null, isLongTermEnabled);
        if (validationResult == OK) {
            return requireNonNull(schedule);
        } else {
            throw new PreCheckException(validationResult);
        }
    }

    /**
     * Given a schedule, consensus time, and long term scheduling enabled flag, validate the transaction
     * meets minimum requirements to be handled. Returns {@link ResponseCodeEnum#OK} if the schedule is valid.
     * <p>
     * This method checks that, as of the current consensus time, the schedule,
     * <ul>
     *     <li>Is not null.</li>
     *     <li>Has a scheduled transaction.</li>
     *     <li>Has not been executed.</li>
     *     <li>Is not deleted.</li>
     *     <li>Has not expired.</li>
     * </ul>
     *
     * @param schedule the schedule to validate
     * @param consensusNow the current consensus time
     * @param isLongTermEnabled whether long term scheduling is enabled
     * @return the validation result
     */
    @NonNull
    protected ResponseCodeEnum validate(
            @Nullable final Schedule schedule, @Nullable final Instant consensusNow, final boolean isLongTermEnabled) {
        if (schedule == null) {
            return INVALID_SCHEDULE_ID;
        }
        if (!schedule.hasScheduledTransaction()) {
            return INVALID_TRANSACTION;
        }
        if (schedule.executed()) {
            return SCHEDULE_ALREADY_EXECUTED;
        }
        if (schedule.deleted()) {
            return SCHEDULE_ALREADY_DELETED;
        }
        final long expiration = schedule.calculatedExpirationSecond();
        final var calculatedExpiration = (expiration != Schedule.DEFAULT.calculatedExpirationSecond()
                ? Instant.ofEpochSecond(expiration)
                : Instant.MAX);
        final var effectiveNow = Objects.requireNonNullElse(consensusNow, Instant.MIN);
        if (calculatedExpiration.getEpochSecond() >= effectiveNow.getEpochSecond()) {
            return OK;
        } else {
            return isLongTermEnabled ? SCHEDULE_PENDING_EXPIRATION : INVALID_SCHEDULE_ID;
        }
    }

    /**
     * Indicates if the given validation result is one that may allow a validated schedule to be executed.
     *
     * @param validationResult the validation result
     * @return if the schedule might be executable
     */
    protected boolean isMaybeExecutable(@NonNull final ResponseCodeEnum validationResult) {
        return validationResult == OK || validationResult == SUCCESS || validationResult == SCHEDULE_PENDING_EXPIRATION;
    }

    /**
     * Tries to execute a schedule, if all conditions are met. Returns true if the schedule was executed.
     *
     * @param context the context
     * @param schedule the schedule to execute
     * @param validationResult the validation result
     * @param isLongTermEnabled the is long term enabled
     * @return if the schedule was executed
     */
    protected boolean tryToExecuteSchedule(
            @NonNull final HandleContext context,
            @NonNull final Schedule schedule,
            @NonNull final List<Key> requiredKeys,
            @NonNull final ResponseCodeEnum validationResult,
            final boolean isLongTermEnabled) {
        requireNonNull(context);
        requireNonNull(schedule);
        requireNonNull(requiredKeys);
        requireNonNull(validationResult);

        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        final var simpleKeyVerifier = simpleKeyVerifierFrom(accountStore, schedule.signatories());
        final VerificationAssistant callback = (k, ignore) -> simpleKeyVerifier.test(k);
        final var remainingKeys = new HashSet<>(requiredKeys);
        remainingKeys.removeIf(
                k -> context.keyVerifier().verificationFor(k, callback).passed());
        final boolean isExpired = validationResult == SCHEDULE_PENDING_EXPIRATION;
        if (canExecute(schedule, remainingKeys, isExpired, isLongTermEnabled)) {
            final var body = childAsOrdinary(schedule);
            context.dispatch(subDispatch(
                            schedule.payerAccountIdOrThrow(),
                            body,
                            simpleKeyVerifier,
                            emptySet(),
                            ScheduleStreamBuilder.class,
                            StakingRewards.ON,
                            DispatchOptions.UsePresetTxnId.NO,
                            customFeeCharging,
                            PropagateFeeChargingStrategy.NO))
                    .scheduleRef(schedule.scheduleId());
            context.savepointStack()
                    .getBaseBuilder(ScheduleStreamBuilder.class)
                    .scheduledTransactionID(body.transactionID());
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns a predicate that verifies a key against a given account store and list of signatories.
     * @param accountStore the account store to use to resolve aliased contract id keys
     * @param signatories the approving signatories
     * @return the key verifier
     */
    public static Predicate<Key> simpleKeyVerifierFrom(
            @NonNull final ReadableAccountStore accountStore, @NonNull final List<Key> signatories) {
        final Set<Key> cryptoSigs = new HashSet<>();
        final Set<ContractID> contractIdSigs = new HashSet<>();
        final Set<ContractID> delegatableContractIdSigs = new HashSet<>();
        signatories.forEach(k -> {
            switch (k.key().kind()) {
                case ED25519, ECDSA_SECP256K1 -> cryptoSigs.add(k);
                case CONTRACT_ID -> contractIdSigs.add(k.contractIDOrThrow());
                case DELEGATABLE_CONTRACT_ID -> delegatableContractIdSigs.add(k.delegatableContractIdOrThrow());
                default -> {
                    // No other key type can be a signatory
                }
            }
        });
        return key -> switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1 -> cryptoSigs.contains(key);
                // A contract id key is only activated by direct authorization
            case CONTRACT_ID -> isAuthorized(key.contractIDOrThrow(), accountStore, contractIdSigs, emptySet());
                // The more permissive "delegatable" key is activated by either type of authorization
            case DELEGATABLE_CONTRACT_ID -> isAuthorized(
                    key.delegatableContractIdOrThrow(), accountStore, delegatableContractIdSigs, contractIdSigs);
            default -> false;
        };
    }

    /**
     * Returns a version of the given schedule marked as executed at the given time.
     *
     * @param schedule the schedule to mark as executed
     * @param consensusNow the time to mark the schedule as executed
     * @return the marked schedule
     */
    protected static @NonNull Schedule markedExecuted(
            @NonNull final Schedule schedule, @NonNull final Instant consensusNow) {
        return schedule.copyBuilder()
                .executed(true)
                .resolutionTime(asTimestamp(consensusNow))
                .build();
    }

    /**
     * Evaluates whether a schedule with given remaining signatories, validation result, and can be executed
     * in the context of long-term scheduling on or off.
     *
     * @param schedule the schedule to execute
     * @param remainingKeys the remaining keys that must sign
     * @param isExpired whether the schedule is expired
     * @param isLongTermEnabled the long term scheduling flag
     * @return boolean indicating if the schedule can be executed
     */
    private boolean canExecute(
            @NonNull final Schedule schedule,
            @NonNull final Set<Key> remainingKeys,
            final boolean isExpired,
            final boolean isLongTermEnabled) {
        // We can only execute if there are no remaining keys required to sign
        if (!remainingKeys.isEmpty()) {
            return false;
        }
        // If long-term transactions are disabled, everything executes immediately
        if (!isLongTermEnabled) {
            return true;
        }
        // Otherwise we can only execute in two cases,
        //   (1) The schedule is allowed to execute immediately, and is not expired.
        //   (2) The schedule is waiting for its expiry to execute, and is expired.
        return schedule.waitForExpiry() == isExpired;
    }

    /**
     * Accumulates the valid signatories from a key structure into a set of signatories.
     *
     * @param signatories the set of signatories to accumulate into
     * @param signingCryptoKeys the signing crypto keys
     * @param key the key structure to accumulate signatories from
     */
    private static void accumulateNewSignatories(
            @NonNull final Set<Key> signatories, @NonNull final Set<Key> signingCryptoKeys, @NonNull final Key key) {
        switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1 -> {
                if (signingCryptoKeys.contains(key)) {
                    signatories.add(key);
                }
            }
            case KEY_LIST -> key.keyListOrThrow()
                    .keys()
                    .forEach(k -> accumulateNewSignatories(signatories, signingCryptoKeys, k));
            case THRESHOLD_KEY -> key.thresholdKeyOrThrow()
                    .keysOrThrow()
                    .keys()
                    .forEach(k -> accumulateNewSignatories(signatories, signingCryptoKeys, k));
        }
    }

    /**
     * Returns true if the given key is a contract id key with a contract number. This includes keys that provide both
     * direct authorization and the weaker "delegatable" authorization.
     * @param key the authorizing key to test
     * @return true if the key is a contract id key with a contract number
     */
    private static boolean isNumericContractIdKey(@NonNull final Key key) {
        return (key.hasContractID() && key.contractIDOrThrow().hasContractNum())
                || (key.hasDelegatableContractId()
                        && key.delegatableContractIdOrThrow().hasContractNum());
    }

    /**
     * Returns true if the given contract id is authorized by either of the given sets of authorized contract ids.
     * If the contract has {@link ContractOneOfType#EVM_ADDRESS}, uses the given account store to attempt to resolve
     * the alias and test the implied contract id.
     * @param contractId the contract id to test
     * @param accountStore the account store to use to resolve aliases
     * @param firstAuthorized the first set of authorized contract ids
     * @param secondAuthorized the second set of authorized contract ids
     * @return true if the contract id is authorized
     */
    private static boolean isAuthorized(
            @NonNull final ContractID contractId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final Set<ContractID> firstAuthorized,
            @NonNull final Set<ContractID> secondAuthorized) {
        var effectiveId = ContractID.DEFAULT;
        if (contractId.hasContractNum()) {
            effectiveId = contractId;
        } else if (contractId.hasEvmAddress()) {
            final var accountId = accountStore.getAccountIDByAlias(
                    contractId.shardNum(), contractId.realmNum(), contractId.evmAddressOrThrow());
            if (accountId != null) {
                effectiveId = ContractID.newBuilder()
                        .shardNum(accountId.shardNum())
                        .realmNum(accountId.realmNum())
                        .contractNum(accountId.accountNumOrThrow())
                        .build();
            }
        }
        return firstAuthorized.contains(effectiveId) || secondAuthorized.contains(effectiveId);
    }
}
