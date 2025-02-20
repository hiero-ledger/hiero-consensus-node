// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code TokenCreate}
 * transaction.
 */
public interface TokenCreateStreamBuilder extends TokenBaseStreamBuilder {
    /**
     * Tracks creation of a new token by number. Even if someday we support creating multiple
     * tokens within a smart contract call, we will still only need to track one created token
     * per child record.
     *
     * @param tokenID the {@link AccountID} of the new token
     * @return this builder
     */
    @NonNull
    TokenCreateStreamBuilder tokenID(@NonNull TokenID tokenID);

    /**
     * Gets the token ID of the token created.
     * @return the token ID of the token created
     */
    TokenID tokenID();

    /**
     * Adds the token relations that are created by auto associations.
     * This information is needed while setting record cache.
     * @param tokenAssociation the token association that is created by auto association
     * @return the builder
     */
    TokenCreateStreamBuilder addAutomaticTokenAssociation(@NonNull TokenAssociation tokenAssociation);
}
