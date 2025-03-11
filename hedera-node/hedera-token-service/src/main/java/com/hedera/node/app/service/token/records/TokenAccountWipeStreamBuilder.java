// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code TokenWipe}
 * transaction.
 */
public interface TokenAccountWipeStreamBuilder extends TokenBaseStreamBuilder {

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
    TokenAccountWipeStreamBuilder newTotalSupply(long newTotalSupply);
}
