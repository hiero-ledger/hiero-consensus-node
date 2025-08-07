// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Represents the context of a single {@code preHandle()}-call.
 *
 * <p>During pre-handle, each transaction handler needs access to the transaction body data (i.e. the "operation"
 * being performed, colloquially also called the "transaction" and "transaction body" although both are more
 * or less technically incorrect). The actual {@link TransactionBody} can be accessed from this context. The body
 * contains the operation, the transaction ID, the originating node, and other information.
 *
 * <p>The main responsibility for a transaction handler during pre-handle is to semantically validate the operation
 * and to gather all required keys. The handler, when created, is preloaded with the correct payer key (which is
 * almost always the same as the transaction body's {@link TransactionID}, except in the case of a scheduled
 * transaction). {@link TransactionHandler}s must add any additional required signing keys. Several convenience
 * methods have been created for this purpose.
 *
 * <p>{@link #requireKey(Key)} is used to add a required non-payer signing key (remember, the payer signing
 * key was added when the context was created). Some basic validation is performed (the key cannot be null or empty).
 */
@SuppressWarnings("UnusedReturnValue")
public interface PreHandleContext extends TransactionKeys {

    /**
     * Gets the {@link TransactionBody}
     *
     * @return the {@link TransactionBody} in this context
     */
    @NonNull
    TransactionBody body();

    /**
     * Gets the payer {@link AccountID}.
     *
     * @return the {@link AccountID} of the payer in this context
     */
    @NonNull
    AccountID payer();

    /**
     * Returns the current {@link Configuration}.
     *
     * @return the {@link Configuration}
     */
    @NonNull
    Configuration configuration();

    /**
     * Returns true if the transaction associated with this context was created by the user, false if the transaction
     * was created by another transaction, or by the system.
     *
     * @return {@code true} this transaction was created by a user, {@code false} if it was a system or child
     *         transaction.
     */
    boolean isUserTransaction();

    /**
     * Returns the opposite of {@link #isUserTransaction()}. A transaction is either a user transaction, or a
     * synthetic transaction, but not both.
     *
     * @return {@code false} this transaction was created by a user, {@code true} if it was a system or child
     *         transaction.
     */
    default boolean isSyntheticTransaction() {
        return !isUserTransaction();
    }

    /**
     * Create a new store given the store's interface. This gives read-only access to the store.
     *
     * @param storeInterface The store interface to find and create a store for
     * @param <C> Interface class for a Store
     * @return An implementation of store interface provided, or null if the store
     * @throws IllegalArgumentException if the storeInterface class provided is unknown to the app
     * @throws NullPointerException if {@code storeInterface} is {@code null}
     */
    @NonNull
    <C> C createStore(@NonNull final Class<C> storeInterface);

    /**
     * Adds the given key to required non-payer keys. If the key is the same as the payer key, or if the key has
     * already been added, then the call is a no-op. The key must not be null.
     *
     * @param key key to be added
     * @return {@code this} object
     * @throws NullPointerException if the key is null
     * @throws PreCheckException if the key is not accepted
     */
    @NonNull
    PreHandleContext requireKey(@NonNull final Key key) throws PreCheckException;

    /**
     * Adds the given key to optional non-payer keys.
     * If the key is invalid, is the same as the payer key, or if the key has already been added, then the call
     * is a no-op. The key must not be null.
     *
     * @param key key to be added
     * @return {@code this} object
     * @throws NullPointerException if the key is null
     * @throws PreCheckException if the key is not accepted
     */
    @NonNull
    PreHandleContext optionalKey(@NonNull final Key key) throws PreCheckException;

    /**
     * Adds the given set of keys to optional non-payer keys.
     * If any key is invalid, is the same as the payer key, or if any key has already been added, then the call
     * ignores that key. The set of keys must not be null, but may be empty.
     *
     * @param keys the set of keys to be added
     * @return {@code this} object
     * @throws NullPointerException if the set of keys is null
     * @throws PreCheckException if the key is not accepted
     */
    @NonNull
    PreHandleContext optionalKeys(@NonNull final Set<Key> keys) throws PreCheckException;

    /**
     * Adds the given hollow account to the optional signing set.
     * If the account has already been added, then the call is a no-op. The account must not be null.
     * During signature verification, the app will verify if the transaction was signed by an ECDSA(secp256k1)
     * key corresponding to the given account's alias. If the verification fails, however, that optional
     * hollow account will be skipped, rather than failing the overall signature verification.
     * If the account provided here is not a hollow account, an exception will be thrown.
     *
     * @param hollowAccount the EVM address alias
     * @return {@code this} object
     * @throws IllegalArgumentException if the account is not a hollow account
     */
    @NonNull
    PreHandleContext optionalSignatureForHollowAccount(@NonNull final Account hollowAccount);

    /**
     * Adds the given key to required non-payer keys. If the key is the same as the payer key, or if the key has
     * already been added, then the call is a no-op. The key must not be null and not empty, otherwise a
     * PreCheckException is thrown with the given {@code responseCode}.
     *
     * @param key key to be added
     * @param responseCode the response code to be used in case the key is null or empty
     * @return {@code this} object
     * @throws PreCheckException if the key is null or empty
     */
    @NonNull
    PreHandleContext requireKeyOrThrow(@Nullable final Key key, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException;

    /**
     * Adds the admin key of the account addressed by the given {@code accountID} to the required non-payer keys. If
     * the key is the same as the payer key, or if the key has already been added, then the call is a no-op. The
     * {@link AccountID} must not be null, and must refer to an actual account. The admin key on that account must not
     * be null or empty. If any of these conditions are not met, a PreCheckException is thrown with the given
     * {@code responseCode}.
     *
     * @param accountID The ID of the account whose key is to be added
     * @param responseCode the response code to be used in case the key is null or empty
     * @return {@code this} object
     * @throws PreCheckException if the key is null or empty or the account is null or the
     * account does not exist.
     */
    @NonNull
    PreHandleContext requireAliasedKeyOrThrow(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode) throws PreCheckException;

    /**
     * Adds the admin key of the account addressed by the given {@code accountID} to the required non-payer keys. If
     * the key is the same as the payer key, or if the key has already been added, then the call is a no-op. The
     * {@link AccountID} must not be null, and must refer to an actual account. The admin key on that account must not
     * be null or empty. If any of these conditions are not met, a PreCheckException is thrown with the given
     * {@code responseCode}.
     * @param accountID The ID of the account whose key is to be added
     * @param responseCode the response code to be used in case the key is null or empty
     * @return {@code this} object
     * @throws PreCheckException if the key is null or empty or the account is null or the
     * account does not exist.
     */
    @NonNull
    PreHandleContext requireKeyOrThrow(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode) throws PreCheckException;

    /**
     * Adds the admin key of the account with the given {@code accountID} to the required non-payer keys. If
     * the key is the same as the payer key, or if the key has already been added, then the call is a no-op.
     * <p>
     * If the {@link AccountID} is null, throws a {@link PreCheckException} with the given {@code failureStatus}.
     * If the account id is not null, throws an appropriate {@link PreCheckException} unless the account id refers to
     * an undeleted, mutable account with an admin key that can provide a cryptographic signature.
     * <p>
     * Client code that wants to fail on a deleted account with a more specific status code than
     * {@link ResponseCodeEnum#ACCOUNT_DELETED} may use {@link PreHandleContext#requireKeyOrThrow(AccountID, ResponseCodeEnum)},
     * which does not fail on deleted accounts.
     *
     * @param accountID The ID of the account whose key is to be added
     * @param failureStatus the response code to be used in case the key is null or empty
     * @return {@code this} object
     * @throws PreCheckException on failure as described above
     */
    @NonNull
    PreHandleContext requireKeyOrThrowOnDeleted(@Nullable AccountID accountID, @NonNull ResponseCodeEnum failureStatus)
            throws PreCheckException;

    /**
     * Adds the admin key of the account addressed by the given {@code accountID} to the required non-payer keys. If
     * the key is the same as the payer key, or if the key has already been added, then the call is a no-op. The
     * {@link AccountID} must not be null, and must refer to an actual account. The admin key on that account must not
     * be null or empty. If any of these conditions are not met, a PreCheckException is thrown with the given
     * {@code responseCode}.
     *
     * @param accountID The ID of the account whose key is to be added
     * @param finisher a function to apply to the key before adding it to the required keys
     * @param failureStatus the response code to be used in case the key is null or empty
     * @return {@code this} object
     * @throws PreCheckException if the key is null or empty or the account is missing or deleted
     */
    @NonNull
    PreHandleContext requireKeyOrThrow(
            @Nullable AccountID accountID,
            @NonNull UnaryOperator<Key> finisher,
            @NonNull ResponseCodeEnum failureStatus)
            throws PreCheckException;

    /**
     * The same as {@link #requireKeyOrThrow(AccountID, ResponseCodeEnum)} but for a {@link ContractID}.
     *
     * @param accountID The ID of the contract account whose key is to be added
     * @param responseCode the response code to be used in case the key is null or empty
     * @return {@code this} object
     * @throws PreCheckException if the key is null or empty or the account is null or the
     * contract account does not exist or the account is not a contract account.
     */
    @NonNull
    PreHandleContext requireKeyOrThrow(
            @Nullable final ContractID accountID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException;

    /**
     * Adds the admin key of the account addressed by the given {@code accountID} to the required non-payer keys if
     * the {@link AccountID} is not null and if the account has `receiverSigRequired` set to true. If the account
     * does not exist, or `receiverSigRequired` is true but the key is null or empty, then a
     * {@link PreCheckException} will be thrown with the supplied {@code responseCode}.
     *
     * @param accountID The ID of the account whose key is to be added
     * @param responseCode the response code to be used if a {@link PreCheckException} is thrown
     * @throws PreCheckException if the account does not exist or the account has `receiverSigRequired` but a null or
     * empty key.
     */
    @NonNull
    PreHandleContext requireKeyIfReceiverSigRequired(
            @Nullable final AccountID accountID, @NonNull final ResponseCodeEnum responseCode) throws PreCheckException;

    /**
     * The same as {@link #requireKeyIfReceiverSigRequired(AccountID, ResponseCodeEnum)} but for a {@link ContractID}.
     *
     * @param contractID The ID of the contract account whose key is to be added
     * @param responseCode the response code to be used if a {@link PreCheckException} is thrown
     * @throws PreCheckException if the account does not exist or the account has `receiverSigRequired` but a null or
     * empty key, or the account exists but is not a contract account.
     */
    @NonNull
    PreHandleContext requireKeyIfReceiverSigRequired(
            @Nullable final ContractID contractID, @NonNull final ResponseCodeEnum responseCode)
            throws PreCheckException;

    /**
     * Adds the given hollow account to the required signing set. If the account has already been added, then
     * the call is a no-op. The account must not be null. During signature verification, the app will verify that the
     * transaction was signed by an ECDSA(secp256k1) key corresponding to the given account's alias. If the account
     * is not a hollow account, an exception will be thrown,
     *
     * @param hollowAccount the EVM address alias
     * @return {@code this} object
     * @throws IllegalArgumentException if the account is not a hollow account
     */
    @NonNull
    PreHandleContext requireSignatureForHollowAccount(@NonNull final Account hollowAccount);

    /**
     * Adds the given hollow account to the required signing set. If the account has already been added, then
     * the call is a no-op. The account must not be null. During signature verification, the app will verify that the
     * transaction was signed by an ECDSA(secp256k1) key corresponding to the given account's alias.
     *
     * @param hollowAccountAlias the EVM address alias
     * @return {@code this} object
     * @throws IllegalArgumentException if the account is not a hollow account
     */
    @NonNull
    PreHandleContext requireSignatureForHollowAccountCreation(@NonNull final Bytes hollowAccountAlias);

    /**
     * Returns all (required and optional) keys of a nested transaction.
     *
     * @param body the {@link TransactionBody} which keys are needed
     * @param payerId the payer for the nested transaction
     * @return the set of keys
     * @throws PreCheckException If there is a problem with the nested transaction
     */
    @NonNull
    TransactionKeys allKeysForTransaction(@NonNull TransactionBody body, @NonNull AccountID payerId)
            throws PreCheckException;

    /**
     * Returns the {@link NodeInfo} of the creator of the transaction.
     */
    NodeInfo creatorInfo();
}
