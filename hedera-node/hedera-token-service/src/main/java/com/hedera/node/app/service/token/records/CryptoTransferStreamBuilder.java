// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A {@code StreamBuilder} specialization for tracking the effects of a {@code CryptoTransfer}
 * transaction.
 */
public interface CryptoTransferStreamBuilder extends StreamBuilder {
    /**
     * Tracks the <b>net</b> hbar transfers that need to be applied to the associated accounts
     * (accounts are specified in the {@code TransferList} input param).
     *
     * @param hbarTransfers the net list of adjustments to make to account balances
     * @return this builder
     */
    @NonNull
    CryptoTransferStreamBuilder transferList(@NonNull TransferList hbarTransfers);

    /**
     * Tracks the <b>net</b> token transfers that need to be applied to the associated accounts,
     * including both fungible and non-fungible types.
     *
     * @param tokenTransferLists the net list of balance or ownership changes for the given
     *                           fungible and non-fungible tokens
     * @return this builder
     */
    @NonNull
    CryptoTransferStreamBuilder tokenTransferLists(@NonNull List<TokenTransferList> tokenTransferLists);

    /**
     * Returns all assessed custom fees for this call.
     *
     * @return the assessed custom fees
     */
    @NonNull
    List<AssessedCustomFee> getAssessedCustomFees();

    /**
     * Tracks the total custom fees assessed in the transaction.
     * @param assessedCustomFees the total custom fees assessed in the transaction
     * @return this builder
     */
    @NonNull
    CryptoTransferStreamBuilder assessedCustomFees(@NonNull List<AssessedCustomFee> assessedCustomFees);

    /**
     * Tracks the total amount of hbars paid as staking rewards in the transaction.
     * @param paidStakingRewards the total amount of hbars paid as staking rewards
     * @return this builder
     */
    CryptoTransferStreamBuilder paidStakingRewards(@NonNull List<AccountAmount> paidStakingRewards);

    /**
     * Adds the token relations that are created by auto associations.
     * This information is needed while building the transfer list, to set the auto association flag.
     * @param tokenAssociation the token association that is created by auto association
     * @return the builder
     */
    CryptoTransferStreamBuilder addAutomaticTokenAssociation(@NonNull TokenAssociation tokenAssociation);
}
