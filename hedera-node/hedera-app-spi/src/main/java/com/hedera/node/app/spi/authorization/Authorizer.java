// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.authorization;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Verifies whether an account is authorized to perform a specific function.
 */
public interface Authorizer {
    /**
     * Checks if the given account is authorized to perform the given function.
     *
     * @param id The ID of the account to check
     * @param function The specific functionality to check
     * @return true if the account is authorized, otherwise false.
     */
    boolean isAuthorized(@NonNull AccountID id, @NonNull HederaFunctionality function);

    /**
     * Checks whether the given account refers to a superuser. If the {@link AccountID} does not contain an account
     * number (for example, because it uses an alias), then this method will return false.
     *
     * @param id The ID of the account to check
     * @return Whether the ID definitively refers to a super-user
     */
    boolean isSuperUser(@NonNull AccountID id);

    /**
     * Checks whether the given account refers to a treasury account. If the {@link AccountID} does not contain an account
     * number (for example, because it uses an alias), then this method will return false.
     *
     * @param id The ID of the account to check
     * @return Whether the ID definitively refers to a super-user
     */
    boolean isTreasury(@NonNull AccountID id);

    /**
     * Checks whether an account is exempt from paying fees.
     *
     * @param id the {@link AccountID} to check
     * @param functionality the {@link HederaFunctionality} of the transaction
     * @param txBody the {@link TransactionBody} of the transaction
     * @return {@code true} if the account is exempt from paying fees, otherwise {@code false}
     */
    SystemPrivilege hasPrivilegedAuthorization(
            @NonNull AccountID id, @NonNull HederaFunctionality functionality, @NonNull TransactionBody txBody);

    /**
     * Checks whether the given information about a transaction implies fees should be waived.
     *
     * @param id the payer {@link AccountID} of the transaction
     * @param functionality the {@link HederaFunctionality} of the transaction
     * @param txBody the {@link TransactionBody} of the transaction
     * @return {@code true} if the transaction should have fees waived, otherwise {@code false}
     */
    default boolean hasWaivedFees(
            @NonNull AccountID id, @NonNull HederaFunctionality functionality, @NonNull TransactionBody txBody) {
        return isSuperUser(id) || hasPrivilegedAuthorization(id, functionality, txBody) == SystemPrivilege.AUTHORIZED;
    }
}
