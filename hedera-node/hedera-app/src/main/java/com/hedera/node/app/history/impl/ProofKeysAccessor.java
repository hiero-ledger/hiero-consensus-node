// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;

/**
 * Provides access to the Schnorr key pairs generated for use in metadata proof constructions.
 */
public interface ProofKeysAccessor {
    /**
     * Returns the Schnorr key pair this node should use starting with the given construction id,
     * creating the key pair if necessary.
     * @param constructionId the active construction ID
     * @return the Schnorr key pair
     */
    SchnorrKeyPair getOrCreateSchnorrKeyPair(long constructionId);
}
