// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.processors.ProcessorModule.NUM_SYSTEM_ACCOUNTS;
import static com.hedera.node.app.service.token.AliasUtils.extractIdFromAddressAlias;
import static com.hedera.node.app.service.token.AliasUtils.isEntityNumAlias;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Small helper to screen whether a {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody} tries
 * to credit a system account. (These accounts are supposed to be effectively invisible inside the EVM.)
 */
public class SystemAccountCreditScreen {
    public static final SystemAccountCreditScreen SYSTEM_ACCOUNT_CREDIT_SCREEN = new SystemAccountCreditScreen();

    private static final TransferAuditTest SYSTEM_ACCOUNT_CREDIT_TEST = new TransferAuditTest() {
        @Override
        public boolean flagsAdjustment(@NonNull final AccountAmount adjust) {
            requireNonNull(adjust);
            return creditsSystemAccount(adjust);
        }

        @Override
        public boolean flagsNftTransfer(@NonNull final NftTransfer nftTransfer) {
            requireNonNull(nftTransfer);
            return isSystemAccountReference(nftTransfer.receiverAccountIDOrThrow());
        }
    };

    private SystemAccountCreditScreen() {
        // Singleton
    }

    /**
     * Returns {@code true} if the given {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody} tries
     * to credit a system account.
     *
     * @param op the {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody} to screen
     * @return whether it credits a system account
     */
    public boolean creditsToSystemAccount(@NonNull final CryptoTransferTransactionBody op) {
        return TransferAuditTest.isAuditFlagRaised(op, SYSTEM_ACCOUNT_CREDIT_TEST);
    }

    private static boolean creditsSystemAccount(@NonNull final AccountAmount adjust) {
        return adjust.amount() > 0 && isSystemAccountReference(adjust.accountIDOrElse(AccountID.DEFAULT));
    }

    private static boolean isSystemAccountNumber(final long number) {
        return number > 0 && number <= NUM_SYSTEM_ACCOUNTS;
    }

    private static boolean isSystemAccountReference(final @NonNull AccountID accountID) {
        if (accountID.hasAccountNum()) {
            return isSystemAccountNumber(accountID.accountNumOrElse(0L));
        }
        if (accountID.hasAlias()) {
            var alias = accountID.aliasOrElse(Bytes.EMPTY);
            return isEntityNumAlias(alias) && isSystemAccountNumber(extractIdFromAddressAlias(alias));
        }

        return false;
    }
}
