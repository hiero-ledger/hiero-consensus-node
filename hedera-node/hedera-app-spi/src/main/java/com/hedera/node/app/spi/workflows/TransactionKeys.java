// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.state.token.Account;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Contains all keys and hollow accounts (required and optional) of a transaction.
 */
public interface TransactionKeys {
    /**
     * Getter for the payer key
     *
     * @return the payer key
     */
    @NonNull
    Key payerKey();

    /**
     * Returns an immutable copy of the set of required non-payer keys.
     *
     * @return the {@link Set} with the required non-payer keys
     */
    @NonNull
    Set<Key> requiredNonPayerKeys();

    /**
     * Gets an immutable copy of the set of required hollow accounts that need signatures.
     *
     * @return the {@link Set} of hollow accounts required
     */
    @NonNull
    Set<Account> requiredHollowAccounts();

    /**
     * Returns an immutable copy of the set of optional non-payer keys.
     *
     * @return the {@link Set} with the optional non-payer keys.  This set may be empty.
     */
    @NonNull
    Set<Key> optionalNonPayerKeys();

    /**
     * Gets an immutable copy of the set of optional hollow accounts that may need signatures.
     *
     * @return the {@link Set} of hollow accounts possibly required
     */
    @NonNull
    Set<Account> optionalHollowAccounts();
}
