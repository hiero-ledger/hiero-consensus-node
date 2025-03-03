// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code TokenBurn}
 * transaction.
 */
public interface TokenBurnStreamBuilder extends TokenBaseStreamBuilder {

    /**
     * Gets the new total supply of a token.
     * @return new total supply of a token
     */
    long getNewTotalSupply();

    /**
     * Sets the new total supply of a token.
     * @param newTotalSupply the new total supply of a token
     * @return this builder
     */
    @NonNull
    TokenBurnStreamBuilder newTotalSupply(long newTotalSupply);

    /**
     * Sets the list of serial numbers burned.
     * @param serialNumbers list of serial numbers burned
     * @return this builder
     */
    @NonNull
    TokenBurnStreamBuilder serialNumbers(@NonNull List<Long> serialNumbers);
}
