// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code CryptoCreate}
 * transaction.
 */
public interface CryptoCreateStreamBuilder extends StreamBuilder {
    /**
     * Tracks creation of a new account by number. Even if someday we support creating multiple
     * accounts within a smart contract call, we will still only need to track one created account
     * per child record.
     *
     * @param accountID the {@link AccountID} of the new account
     * @return this builder
     */
    @NonNull
    CryptoCreateStreamBuilder accountID(@NonNull AccountID accountID);

    /**
     * The new EVM address of the account created by this transaction.
     * @param evmAddress the new EVM address
     * @return this builder
     */
    @NonNull
    CryptoCreateStreamBuilder evmAddress(@NonNull Bytes evmAddress);

    /**
     * The transactionFee charged for this transaction.
     * @param transactionFee the transaction fee
     * @return this builder
     */
    @NonNull
    CryptoCreateStreamBuilder transactionFee(@NonNull long transactionFee);

    /**
     * The memo associated with the transaction.
     * @param memo the memo
     * @return this builder
     */
    @NonNull
    CryptoCreateStreamBuilder memo(@NonNull String memo);
}
