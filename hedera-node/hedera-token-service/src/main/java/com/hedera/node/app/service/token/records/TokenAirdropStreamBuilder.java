// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code RecordBuilder} specialization for tracking the effects of a {@code TokenAirdrops}
 * transaction.
 */
public interface TokenAirdropStreamBuilder extends CryptoTransferStreamBuilder {
    /**
     * Adds to the pending airdrop record list.
     *
     * @param pendingAirdropRecord pending airdrop record
     * @return the builder
     */
    TokenAirdropStreamBuilder addPendingAirdrop(@NonNull PendingAirdropRecord pendingAirdropRecord);
}
