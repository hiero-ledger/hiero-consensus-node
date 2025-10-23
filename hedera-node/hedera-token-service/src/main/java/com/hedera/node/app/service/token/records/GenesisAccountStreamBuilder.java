// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} that collects and builds the information required to create a synthetic
 * account record, specifically for a system account created during node genesis (startup).
 */
public interface GenesisAccountStreamBuilder extends StreamBuilder {

    /**
     * Tracks the created account ID for the system account.
     * @param accountID the account ID of the system account
     * @return this builder
     */
    @NonNull
    GenesisAccountStreamBuilder accountID(@NonNull AccountID accountID);

    /**
     * Tracks the synthetic transaction that represents the created system account.
     *
     * @param txn the synthetic transaction that represents the created system account
     * @return this builder
     */
    @NonNull
    GenesisAccountStreamBuilder signedTx(@NonNull SignedTransaction txn);

    /**
     * Tracks the synthetic transaction that represents the created system account.
     * @param status the status of the synthetic transaction that represents the created system account
     * @return this builder
     */
    @NonNull
    GenesisAccountStreamBuilder status(@NonNull ResponseCodeEnum status);

    /**
     * Tracks the memo for the synthetic record.
     * @param memo the memo for the synthetic record
     * @return this builder
     */
    @NonNull
    GenesisAccountStreamBuilder memo(@NonNull String memo);

    /**
     * Tracks the <b>net</b> hbar transfers that need to be applied to the associated accounts
     * (accounts are specified in the {@code TransferList} input param).
     *
     * @param hbarTransfers the net list of adjustments to make to account balances
     * @return this builder
     */
    @NonNull
    CryptoTransferStreamBuilder transferList(@NonNull TransferList hbarTransfers);
}
