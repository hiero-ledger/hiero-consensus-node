// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
import static com.hedera.node.app.service.token.impl.util.TokenHandlerHelper.verifyNotEmptyKey;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Key.KeyOneOfType;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionKeys;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.purechecks.PureChecksContextImpl;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Implementation of {@link PreHandleContext}.
 */
public class PreHandleContextImpl implements PreHandleContext {

    /**
     * Used to get keys for accounts and contracts.
     */
    private final ReadableAccountStore accountStore;
    /**
     * The transaction body.
     */
    private final TransactionBody txn;
    /**
     * The payer account ID. Specified in the transaction body, extracted and stored separately for convenience.
     */
    private final AccountID payerId;
    /**
     * The payer's key, as found in state
     */
    private final Key payerKey;
    /**
     * The set of all required non-payer keys. A {@link LinkedHashSet} is used to maintain a consistent ordering.
     * While not strictly necessary, it is useful at the moment to ensure tests are deterministic. The tests should
     * be updated to compare set contents rather than ordering.
     */
    private final Set<Key> requiredNonPayerKeys = new LinkedHashSet<>();
    /**
     * The set of all hollow accounts that need to be validated.
     */
    private final Set<Account> requiredHollowAccounts = new LinkedHashSet<>();
    /**
     * The set of all optional non-payer keys. A {@link LinkedHashSet} is used to maintain a consistent ordering.
     * While not strictly necessary, it is useful at the moment to ensure tests are deterministic. The tests should
     * be updated to compare set contents rather than ordering.
     */
    private final Set<Key> optionalNonPayerKeys = new LinkedHashSet<>();
    /**
     * The set of all hollow accounts that <strong>might</strong> need to be validated, but also might not.
     */
    private final Set<Account> optionalHollowAccounts = new LinkedHashSet<>();

    private final ReadableStoreFactory storeFactory;
    private final NodeInfo creatorInfo;

    /**
     * Configuration to be used during pre-handle
     */
    private final Configuration configuration;

    private final TransactionDispatcher dispatcher;
    private final boolean isUserTx;
    private final TransactionChecker transactionChecker;

    public PreHandleContextImpl(
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final TransactionBody txn,
            @NonNull final Configuration configuration,
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final TransactionChecker transactionChecker,
            @NonNull final NodeInfo creatorInfo)
            throws PreCheckException {
        this(
                storeFactory,
                txn,
                txn.transactionIDOrElse(TransactionID.DEFAULT).accountIDOrElse(AccountID.DEFAULT),
                creatorInfo,
                configuration,
                dispatcher,
                true,
                transactionChecker);
    }

    public PreHandleContextImpl(
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final TransactionBody txn,
            @NonNull final AccountID payer,
            @NonNull final Configuration configuration,
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final TransactionChecker transactionChecker,
            @NonNull final NodeInfo creatorInfo)
            throws PreCheckException {
        this(storeFactory, txn, payer, creatorInfo, configuration, dispatcher, false, transactionChecker);
    }

    /**
     * Create a new instance of {@link PreHandleContextImpl}.
     * @throws PreCheckException if the payer account does not exist
     */
    private PreHandleContextImpl(
            @NonNull final ReadableStoreFactory storeFactory,
            @NonNull final TransactionBody txn,
            @NonNull final AccountID payerId,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final Configuration configuration,
            @NonNull final TransactionDispatcher dispatcher,
            final boolean isUserTx,
            @NonNull final TransactionChecker transactionChecker)
            throws PreCheckException {
        this.storeFactory = requireNonNull(storeFactory, "storeFactory must not be null.");
        this.txn = requireNonNull(txn, "txn must not be null!");
        this.payerId = requireNonNull(payerId, "payer must not be null!");
        this.creatorInfo = requireNonNull(creatorInfo);
        this.configuration = requireNonNull(configuration, "configuration must not be null!");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher must not be null!");
        this.isUserTx = isUserTx;
        this.accountStore = storeFactory.getStore(ReadableAccountStore.class);
        // Find the account, which must exist or throw on construction
        final var payer = mustExist(accountStore.getAccountById(payerId), INVALID_PAYER_ACCOUNT_ID);
        // It would be a catastrophic invariant failure if an account in state didn't have a key
        payerKey = payer.keyOrThrow();
        this.transactionChecker = requireNonNull(transactionChecker, "transactionChecker must not be null!");
    }

    @Override
    @NonNull
    public <C> C createStore(@NonNull Class<C> storeInterface) {
        return storeFactory.getStore(storeInterface);
    }

    @Override
    @NonNull
    public TransactionBody body() {
        return txn;
    }

    @Override
    @NonNull
    public AccountID payer() {
        return payerId;
    }

    @Override
    @NonNull
    public Configuration configuration() {
        return configuration;
    }

    @Override
    public boolean isUserTransaction() {
        return isUserTx;
    }

    @NonNull
    @Override
    public Set<Key> requiredNonPayerKeys() {
        return Collections.unmodifiableSet(requiredNonPayerKeys);
    }

    @NonNull
    @Override
    public Set<Key> optionalNonPayerKeys() {
        return Collections.unmodifiableSet(optionalNonPayerKeys);
    }

    @NonNull
    @Override
    public PreHandleContext optionalKey(@NonNull final Key key) throws PreCheckException {
        // Verify this key isn't for an immutable account
        verifyNotEmptyKey(key, ResponseCodeEnum.INVALID_ACCOUNT_ID);

        if (!key.equals(payerKey) && isValid(key)) {
            optionalNonPayerKeys.add(key);
        }
        return this;
    }

    @NonNull
    @Override
    public PreHandleContext optionalKeys(@NonNull final Set<Key> keys) throws PreCheckException {
        for (final Key nextKey : keys) {
            optionalKey(nextKey);
        }
        return this;
    }

    @NonNull
    @Override
    public Set<Account> optionalHollowAccounts() {
        return Collections.unmodifiableSet(optionalHollowAccounts);
    }

    @NonNull
    @Override
    public PreHandleContext optionalSignatureForHollowAccount(@NonNull final Account hollowAccount) {
        requireNonNull(hollowAccount);
        final AccountID id = hollowAccount.accountId();
        if (!isHollow(hollowAccount)) {
            throw new IllegalArgumentException("Account %d.%d.%d is not a hollow account"
                    .formatted(id.shardNum(), id.realmNum(), id.accountNum()));
        }
        optionalHollowAccounts.add(hollowAccount);
        return this;
    }

    @Override
    @NonNull
    public Set<Account> requiredHollowAccounts() {
        return Collections.unmodifiableSet(requiredHollowAccounts);
    }

    @Override
    @NonNull
    public Key payerKey() {
        return payerKey;
    }

    @Override
    @NonNull
    public PreHandleContext requireKey(@NonNull final Key key) {
        if (!key.equals(payerKey) && isValid(key)) {
            requiredNonPayerKeys.add(key);
        }
        return this;
    }

    @Override
    @NonNull
    @SuppressWarnings(
            "java:S2637") // requireKey accepts "@NonNull" but warning states that null could be passed, seems like
    // false positive because of the !isValid(key) check
    public PreHandleContext requireKeyOrThrow(@Nullable final Key key, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        if (!isValid(key)) {
            throw new PreCheckException(responseCode);
        }

        // Verify this key isn't for an immutable account
        verifyNotEmptyKey(key, responseCode);
        return requireKey(key);
    }

    @Override
    @NonNull
    public PreHandleContext requireKeyOrThrow(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        return requireKey(accountID, responseCode, false, null, false);
    }

    @NonNull
    @Override
    public PreHandleContext requireKeyOrThrowOnDeleted(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum failureStatus)
            throws PreCheckException {
        requireNonNull(failureStatus);
        return requireKey(accountID, failureStatus, false, null, true);
    }

    @NonNull
    @Override
    public PreHandleContext requireKeyOrThrow(
            @Nullable final AccountID accountID,
            @NonNull final UnaryOperator<Key> finisher,
            @NonNull final ResponseCodeEnum failureStatus)
            throws PreCheckException {
        requireNonNull(finisher);
        requireNonNull(failureStatus);
        return requireKey(accountID, failureStatus, false, finisher, true);
    }

    @Override
    @NonNull
    @SuppressWarnings(
            "java:S2637") // requireKey accepts "@NonNull" but warning states that null could be passed, seems like
    // false positive because of the !isValid(key) check
    public PreHandleContext requireAliasedKeyOrThrow(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        return requireKey(accountID, responseCode, true, null, false);
    }

    private @NonNull PreHandleContext requireKey(
            final @Nullable AccountID accountID,
            final @NonNull ResponseCodeEnum responseCode,
            final boolean allowAliasedIds,
            @Nullable final UnaryOperator<Key> finisher,
            final boolean failOnDeleted)
            throws PreCheckException {
        if (accountID == null) {
            throw new PreCheckException(responseCode);
        }
        // Immediately return if we would just repeat the payer requirement; note that correctness
        // of signing requirements for children dispatched by the contract service depends on this.
        // If we repeated the payer requirement, we would be requiring "double authorization" from
        // the contract doing the dispatch; but the contract has already authorized the action by
        // the very execution of its bytecode.
        if (accountID.equals(payerId)) {
            return this;
        }
        final Account account;
        if (allowAliasedIds) {
            account = accountStore.getAliasedAccountById(accountID);
        } else {
            account = accountStore.getAccountById(accountID);
        }
        if (account == null) {
            throw new PreCheckException(responseCode);
        }
        if (failOnDeleted && account.deleted()) {
            throw new PreCheckException(ACCOUNT_DELETED);
        }
        // If it is hollow account, and we require this to sign, we need to finalize the account
        // with the corresponding ECDSA key in handle
        if (isHollow(account)) {
            requiredHollowAccounts.add(account);
            return this;
        }
        // Verify this key isn't for an immutable account
        verifyNotStakingAccounts(account.accountIdOrThrow(), responseCode);
        var key = account.keyOrThrow();
        if (!isValid(key)) { // Or if it is a Contract Key? Or if it is an empty key?
            // Or a KeyList with no
            // keys? Or KeyList with Contract keys only?
            throw new PreCheckException(responseCode);
        }
        if (finisher != null) {
            key = finisher.apply(key);
        }
        return requireKey(key);
    }

    @Override
    @NonNull
    public PreHandleContext requireKeyOrThrow(
            @Nullable final ContractID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        if (accountID == null) {
            throw new PreCheckException(responseCode);
        }

        final var account = accountStore.getContractById(accountID);
        if (account == null) {
            throw new PreCheckException(responseCode);
        }
        // If it is hollow account, and we require this to sign, we need to finalize the account
        // with the corresponding ECDSA key in handle
        if (isHollow(account)) {
            requiredHollowAccounts.add(account);
            return this;
        }
        // Verify this key isn't for an immutable account
        verifyNotStakingAccounts(account.accountIdOrThrow(), responseCode);
        final var key = account.key();
        if (!isValid(key)) { // Or if it is a Contract Key? Or if it is an empty key?
            // Or a KeyList with no
            // keys? Or KeyList with Contract keys only?
            throw new PreCheckException(responseCode);
        }
        return requireKey(key);
    }

    @Override
    @NonNull
    public PreHandleContext requireKeyIfReceiverSigRequired(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        // If no accountID is specified, then there is no key to require.
        if (accountID == null || accountID.equals(AccountID.DEFAULT)) {
            return this;
        }

        // If an accountID is specified, then the account MUST exist
        final var account = accountStore.getAccountById(accountID);
        if (account == null) {
            throw new PreCheckException(responseCode);
        }

        // If the account exists but does not require a signature, then there is no key to require.
        if (!account.receiverSigRequired()) {
            return this;
        }
        // If it is hollow account, and we require this to sign, we need to finalize the account
        // with the corresponding ECDSA key in handle
        if (isHollow(account)) {
            requiredHollowAccounts.add(account);
            return this;
        }
        // Verify this key isn't for an immutable account
        verifyNotStakingAccounts(account.accountIdOrThrow(), responseCode);
        // We will require the key. If the key isn't present, then we will throw the given response code.
        final var key = account.key();
        if (key == null
                || key.key().kind() == KeyOneOfType.UNSET) { // Or if it is a Contract Key? Or if it is an empty key?
            // Or a KeyList with no
            // keys? Or KeyList with Contract keys only?
            throw new PreCheckException(responseCode);
        }
        return requireKey(key);
    }

    @Override
    @NonNull
    public PreHandleContext requireKeyIfReceiverSigRequired(
            @Nullable final ContractID contractID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        // If no accountID is specified, then there is no key to require.
        if (contractID == null) {
            return this;
        }

        // If an accountID is specified, then the account MUST exist
        final var account = accountStore.getContractById(contractID);
        if (account == null) {
            throw new PreCheckException(responseCode);
        }

        // If the account exists but does not require a signature, then there is no key to require.
        if (!account.receiverSigRequired()) {
            return this;
        }
        // If it is hollow account, and we require this to sign, we need to finalize the account
        // with the corresponding ECDSA key in handle
        if (isHollow(account)) {
            requiredHollowAccounts.add(account);
            return this;
        }
        // Verify this key isn't for an immutable account
        verifyNotStakingAccounts(account.accountIdOrThrow(), responseCode);
        // We will require the key. If the key isn't present, then we will throw the given response code.
        final var key = account.key();
        if (!isValid(key)) { // Or if it is a Contract Key? Or if it is an empty key?
            // Or a KeyList with no
            // keys? Or KeyList with Contract keys only?
            throw new PreCheckException(responseCode);
        }
        return requireKey(key);
    }

    @Override
    @NonNull
    public PreHandleContext requireSignatureForHollowAccount(@NonNull final Account hollowAccount) {
        requireNonNull(hollowAccount);
        final AccountID id = hollowAccount.accountId();
        if (!isHollow(hollowAccount)) {
            throw new IllegalArgumentException("Account %d.%d.%d is not a hollow account"
                    .formatted(id.shardNum(), id.realmNum(), id.accountNum()));
        }

        requiredHollowAccounts.add(hollowAccount);
        return this;
    }

    @Override
    @NonNull
    public PreHandleContext requireSignatureForHollowAccountCreation(@NonNull final Bytes hollowAccountAlias) {
        requireNonNull(hollowAccountAlias);
        requiredHollowAccounts.add(Account.newBuilder()
                .accountId(AccountID.DEFAULT)
                .key(EMPTY_KEY_LIST)
                .alias(hollowAccountAlias)
                .build());
        return this;
    }

    @NonNull
    @Override
    public TransactionKeys allKeysForTransaction(@NonNull TransactionBody body, @NonNull final AccountID payerId)
            throws PreCheckException {
        // Throws PreCheckException if the transaction body is structurally invalid
        final var pureChecksContext = new PureChecksContextImpl(body, dispatcher);
        dispatcher.dispatchPureChecks(pureChecksContext);
        // Throws PreCheckException if the payer account does not exist
        final var context = new PreHandleContextImpl(
                storeFactory, body, payerId, configuration, dispatcher, transactionChecker, creatorInfo);
        try {
            // Accumulate all required keys in the context
            dispatcher.dispatchPreHandle(context);
        } catch (final PreCheckException ignored) {
            // Translate all prehandle failures to unresolvable required signers
            throw new PreCheckException(UNRESOLVABLE_REQUIRED_SIGNERS);
        }
        return context;
    }

    @Override
    public Key getAccountKey(@NonNull final AccountID accountID) throws PreCheckException {
        requireNonNull(accountID);
        final var account = accountStore.getAccountById(accountID);
        if (account == null) {
            throw new PreCheckException(INVALID_ACCOUNT_ID);
        }
        if (account.deleted()) {
            throw new PreCheckException(ACCOUNT_DELETED);
        }
        // Verify this key isn't for an immutable account
        verifyNotStakingAccounts(account.accountIdOrThrow(), INVALID_ACCOUNT_ID);
        return account.keyOrThrow();
    }

    @Override
    public NodeInfo creatorInfo() {
        return creatorInfo;
    }

    @Override
    public String toString() {
        return "PreHandleContextImpl{" + "accountStore="
                + accountStore + ", txn="
                + txn + ", payerId="
                + payerId + ", payerKey="
                + payerKey + ", requiredNonPayerKeys="
                + requiredNonPayerKeys + ", storeFactory="
                + storeFactory + '}';
    }

    /**
     * Checks that an account does not represent one of the staking accounts
     * Throws a {@link PreCheckException} with the designated response code otherwise.
     * @param accountID the accountID to check
     * @param responseCode the response code to throw
     * @throws PreCheckException if the account is considered immutable
     */
    private void verifyNotStakingAccounts(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        final var accountNum = accountID != null ? accountID.accountNum() : 0;
        final var accountsConfig = configuration.getConfigData(AccountsConfig.class);
        if (accountNum == accountsConfig.stakingRewardAccount() || accountNum == accountsConfig.nodeRewardAccount()) {
            throw new PreCheckException(responseCode);
        }
    }
}
