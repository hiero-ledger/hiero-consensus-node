// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code CryptoCreate}
 * transaction.
 */
public interface TokenMintStreamBuilder extends TokenBaseStreamBuilder {
    /**
     * Tracks creation of a new account by number. Even if someday we support creating multiple
     * accounts within a smart contract call, we will still only need to track one created account
     * per child record.
     *
     * @param serialNumbers the list of new serial numbers minted
     * @return this builder
     */
    @NonNull
    TokenMintStreamBuilder serialNumbers(@NonNull List<Long> serialNumbers);

    /**
     * Sets the new total supply of a token.
     * @param newTotalSupply the new total supply of a token
     * @return this builder
     */
    TokenMintStreamBuilder newTotalSupply(long newTotalSupply);

    /**
     * Gets the new total supply of a token.
     * @return new total supply of a token
     */
    long getNewTotalSupply();
}
