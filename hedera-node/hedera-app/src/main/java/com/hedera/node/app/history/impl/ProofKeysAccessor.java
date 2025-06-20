// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import com.hedera.node.app.history.impl.ProofKeysAccessorImpl.SchnorrKeyPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides access to the Schnorr key pairs generated for use in metadata proof constructions.
 */
public interface ProofKeysAccessor {
    /**
     * Signs the given message with the Schnorr key this node should use for the given construction id.
     * @param constructionId the active construction ID
     * @param message the message to sign
     * @return the signature, using the Schnorr private key for the given construction ID
     */
    Bytes sign(long constructionId, @NonNull Bytes message);

    /**
     * Returns the Schnorr key pair this node should use starting with the given construction id,
     * creating the key pair if necessary.
     * @param constructionId the active construction ID
     * @return the Schnorr key pair
     */
    SchnorrKeyPair getOrCreateSchnorrKeyPair(long constructionId);
}
