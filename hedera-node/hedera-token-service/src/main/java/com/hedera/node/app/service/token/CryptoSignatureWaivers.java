// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.SigWaivers;

/** Signature waivers needed for transactions in {@link TokenService}. */
public interface CryptoSignatureWaivers extends SigWaivers {
    /**
     * Advises if the target account's key must sign a given crypto update. Since accounts 0.0.2 and
     * 0.0.50 can update any non-0.0.2 system accounts with no other signatures. Checks if the
     * target account being updated by the above accounts.
     *
     * @param cryptoUpdateTxn a crypto update transaction
     * @param payer the account paying for the transaction
     * @return whether the target account's key must sign
     */
    boolean isTargetAccountSignatureWaived(TransactionBody cryptoUpdateTxn, AccountID payer);

    /**
     * Advises if the new key for an account must sign a given crypto update. Since accounts 0.0.2
     * and 0.0.50 can update any non-0.0.2 system accounts with no other signatures. Checks if the
     * target account being updated by the above accounts. When updating the treasury account 0.0.2
     * key, the new key must also sign the transaction. Checks if the account being updated is
     * treasury account.
     *
     * @param cryptoUpdateTxn a crypto update transaction
     * @param payer the account paying for the transaction
     * @return whether the new key from the transaction must sign
     */
    boolean isNewKeySignatureWaived(TransactionBody cryptoUpdateTxn, AccountID payer);
}
