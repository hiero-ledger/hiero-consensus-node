// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.records;

import com.hedera.hapi.node.base.TokenAssociation;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the effects of a {@code TokenUpdate}
 * transaction.
 */
public interface TokenUpdateStreamBuilder extends TokenBaseStreamBuilder {
    /**
     * Adds the token relations that are created by auto associations.
     * This information is needed while building the transfer list, to set the auto association flag.
     * @param tokenAssociation the token association that is created by auto association
     * @return the builder
     */
    TokenUpdateStreamBuilder addAutomaticTokenAssociation(@NonNull TokenAssociation tokenAssociation);
}
