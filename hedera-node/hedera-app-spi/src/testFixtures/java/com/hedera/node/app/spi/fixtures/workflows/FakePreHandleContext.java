// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.fixtures.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isValid;
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
import com.hedera.node.app.spi.fixtures.info.FakeNetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionKeys;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Fake implementation of {@link PreHandleContext} to simplify moving forward without breaking all kinds of tests
 * in services.
 *
 * @deprecated for removal in favor of a mock of {@link PreHandleContext}
 */
@Deprecated(forRemoval = true)
public class FakePreHandleContext implements PreHandleContext {

    /** Used to get keys for accounts and contracts. */
    private final ReadableAccountStore accountStore;
    /** The transaction body. */
    private final TransactionBody txn;
    /** The payer account ID. Specified in the transaction body, extracted and stored separately for convenience. */
    private final AccountID payer;
    /** The payer's key, as found in state */
    private final Key payerKey;
    /**
     * The set of all required non-payer keys. A {@link LinkedHashSet} is used to maintain a consistent ordering.
     * While not strictly necessary, it is useful at the moment to ensure tests are deterministic. The tests should
     * be updated to compare set contents rather than ordering.
     */
    private final Set<Key> requiredNonPayerKeys = new LinkedHashSet<>();
    /** The set of all hollow accounts that need to be validated. */
    private final Set<Account> requiredHollowAccounts = new LinkedHashSet<>();
    /**
     * The set of all optional non-payer keys. A {@link LinkedHashSet} is used to maintain a consistent ordering.
     * While not strictly necessary, it is useful at the moment to ensure tests are deterministic. The tests should
     * be updated to compare set contents rather than ordering.
     */
    private final Set<Key> optionalNonPayerKeys = new LinkedHashSet<>();
    /** The set of all hollow accounts that <strong>might</strong> need to be validated, but also might not. */
    private final Set<Account> optionalHollowAccounts = new LinkedHashSet<>();

    private final boolean userTransaction;
    private final Map<Class<?>, Object> stores = new ConcurrentHashMap<>();
    private Configuration configuration;

    /**
     * Create a new PreHandleContext instance. The payer and key will be extracted from the transaction body.
     *
     * @param accountStore used to get keys for accounts and contracts
     * @param txn the transaction body
     * @throws PreCheckException if the payer account ID is invalid or the key is null
     */
    public FakePreHandleContext(@NonNull final ReadableAccountStore accountStore, @NonNull final TransactionBody txn)
            throws PreCheckException {
        this(
                accountStore,
                txn,
                txn.transactionIDOrElse(TransactionID.DEFAULT).accountIDOrElse(AccountID.DEFAULT),
                true);
    }

    public FakePreHandleContext(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TransactionBody txn,
            @NonNull final Configuration configuration)
            throws PreCheckException {
        this(accountStore, txn);
        this.configuration = configuration;
    }

    /** Create a new instance */
    private FakePreHandleContext(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TransactionBody txn,
            @NonNull final AccountID payer,
            final boolean userTransaction)
            throws PreCheckException {
        this.accountStore = requireNonNull(accountStore, "The supplied argument 'accountStore' cannot be null!");
        this.txn = requireNonNull(txn, "The supplied argument 'txn' cannot be null!");
        this.payer = requireNonNull(payer, "The supplied argument 'payer' cannot be null!");
        this.userTransaction = userTransaction;

        stores.put(ReadableAccountStore.class, accountStore);
        // Find the account, which must exist or throw a PreCheckException with the given response code.
        final var account = accountStore.getAccountById(payer);
        mustExist(account, INVALID_PAYER_ACCOUNT_ID);
        // NOTE: While it is true that the key can be null on some special accounts like
        // account 800, those accounts cannot be the payer.
        payerKey = account.key();
        mustExist(payerKey, INVALID_PAYER_ACCOUNT_ID);
    }

    @Override
    @NonNull
    public <C> C createStore(@NonNull final Class<C> storeInterface) {
        requireNonNull(storeInterface, "The supplied argument 'storeInterface' cannot be null.");
        final var store = stores.get(storeInterface);
        if (store != null) {
            return storeInterface.cast(store);
        }
        throw new IllegalArgumentException("No store for " + storeInterface);
    }

    public <T> void registerStore(@NonNull final Class<T> storeInterface, @NonNull final T store) {
        requireNonNull(storeInterface, "The supplied argument 'storeInterface' cannot be null.");
        requireNonNull(store, "The supplied argument 'store' cannot be null.");
        stores.put(storeInterface, store);
    }

    @Override
    @NonNull
    public TransactionBody body() {
        return txn;
    }

    @Override
    @NonNull
    public AccountID payer() {
        return payer;
    }

    @Override
    @NonNull
    public Configuration configuration() {
        return configuration;
    }

    @Override
    public boolean isUserTransaction() {
        return userTransaction;
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
    public PreHandleContext optionalKey(@NonNull final Key key) {
        if (!key.equals(payerKey) && isValid(key)) {
            optionalNonPayerKeys.add(key);
        }
        return this;
    }

    @NonNull
    @Override
    public PreHandleContext optionalKeys(@NonNull final Set<Key> keys) {
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
        final AccountID accountID = hollowAccount.accountId();
        if (!isHollow(hollowAccount)) {
            throw new IllegalArgumentException("Account %d.%d.%d is not a hollow account"
                    .formatted(accountID.shardNum(), accountID.realmNum(), accountID.accountNum()));
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
        return requireKey(key);
    }

    @NonNull
    @Override
    public PreHandleContext requireAliasedKeyOrThrow(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException {
        requireNonNull(responseCode);
        return requireKey(accountID, responseCode, true, null, false);
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

    private @NonNull PreHandleContext requireKey(
            final @Nullable AccountID accountID,
            final @NonNull ResponseCodeEnum responseCode,
            boolean allowAliases,
            @Nullable final UnaryOperator<Key> finisher,
            final boolean failOnDeleted)
            throws PreCheckException {
        if (accountID == null) {
            throw new PreCheckException(responseCode);
        }
        final Account account;
        if (allowAliases) {
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

        var key = account.key();
        if (!isValid(key)) { // Or if it is a Contract Key? Or if it is an empty key?
            // Or a KeyList with no
            // keys? Or KeyList with Contract keys only?
            throw new PreCheckException(responseCode);
        }

        // Verify this key isn't for an immutable account
        verifyIsNotImmutableAccount(key, responseCode);

        if (finisher != null) {
            key = finisher.apply(key);
        }
        return requireKey(key);
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

    @NonNull
    @Override
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
    public TransactionKeys allKeysForTransaction(@NonNull TransactionBody body, @NonNull AccountID payerId) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Key getAccountKey(@NonNull final AccountID accountID) {
        return requireNonNull(accountStore.getAccountById(accountID)).keyOrThrow();
    }

    @Override
    public NodeInfo creatorInfo() {
        return FakeNetworkInfo.FAKE_NODE_INFOS.getFirst();
    }

    @Override
    public String toString() {
        return "FakePreHandleContext{" + "accountStore="
                + accountStore + ", txn="
                + txn + ", payer="
                + payer + ", payerKey="
                + payerKey + ", requiredNonPayerKeys="
                + requiredNonPayerKeys + ", stores="
                + stores + '}';
    }

    /**
     * THIS IS A COPY of {@code verifyIsNotImmutableAccount} in the token service package. It should
     * NOT exist here, but needs to in order for this class to function correctly. However, it
     * should be removed as soon as possible (along with this deprecated class).
     * <p>
     * Checks that a key does not represent an immutable account, e.g. the staking rewards account.
     * Throws a {@link PreCheckException} with the designated response code otherwise.
     * @param key the key to check
     * @param responseCode the response code to throw
     * @throws PreCheckException if the account is considered immutable
     */
    private static void verifyIsNotImmutableAccount(
            @Nullable final Key key, @NonNull final ResponseCodeEnum responseCode) throws PreCheckException {
        if (EMPTY_KEY_LIST.equals(key)) {
            throw new PreCheckException(responseCode);
        }
    }
}
