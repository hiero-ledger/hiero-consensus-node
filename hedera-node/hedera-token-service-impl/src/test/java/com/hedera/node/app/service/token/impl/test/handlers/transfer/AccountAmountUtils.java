// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.transfer;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Utility class for creating {@link AccountAmount} and {@link NftTransfer} objects.
 */
public final class AccountAmountUtils {
    private AccountAmountUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Create an {@link AccountAmount} object with the given account and amount.
     * @param account the account
     * @param amount the amount
     * @return the {@link AccountAmount} object
     */
    public static AccountAmount aaWith(AccountID account, long amount) {
        return AccountAmount.newBuilder().accountID(account).amount(amount).build();
    }

    /**
     * Create an {@link AccountAmount} object with the given account and amount, and set the approval flag.
     * @param account the account
     * @param amount the amount
     * @return the {@link AccountAmount} object
     */
    public static AccountAmount aaWithAllowance(AccountID account, long amount) {
        return AccountAmount.newBuilder()
                .accountID(account)
                .amount(amount)
                .isApproval(true)
                .build();
    }

    /**
     * Create an {@link AccountID} object with the given alias.
     * @param alias the alias
     * @return the {@link AccountID} object
     */
    public static AccountID asAccountWithAlias(Bytes alias) {
        return AccountID.newBuilder().alias(alias).build();
    }

    /**
     * Create an {@link NftTransfer} object with the given sender, receiver, and serial number.
     * @param from the sender
     * @param to the receiver
     * @param serialNo the serial number
     * @return the {@link NftTransfer} object
     */
    public static NftTransfer nftTransferWith(AccountID from, AccountID to, long serialNo) {
        return NftTransfer.newBuilder()
                .senderAccountID(from)
                .receiverAccountID(to)
                .serialNumber(serialNo)
                .build();
    }

    /**
     * Create an {@link NftTransfer} object with the given sender, receiver, and serial number, and set the approval flag to true.
     * @param from the sender
     * @param to the receiver
     * @param serialNo the serial number
     * @return the {@link NftTransfer} object
     */
    public static NftTransfer nftTransferWithAllowance(AccountID from, AccountID to, long serialNo) {
        return NftTransfer.newBuilder()
                .senderAccountID(from)
                .receiverAccountID(to)
                .serialNumber(serialNo)
                .isApproval(true)
                .build();
    }

    /**
     * Create an {@link AccountAmount} object with the given aliased accountId with given alias and amount.
     * @param alias the alias
     * @param amount the amount
     * @return the {@link AccountAmount} object
     */
    public static AccountAmount aaAlias(final AccountID alias, final long amount) {
        return AccountAmount.newBuilder().amount(amount).accountID(alias).build();
    }
}
