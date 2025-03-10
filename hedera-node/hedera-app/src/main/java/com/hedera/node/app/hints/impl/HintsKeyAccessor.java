// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides access to the BLS key pairs generated for use in hinTS constructions.
 */
public interface HintsKeyAccessor {
    /**
     * Signs the given message with the BLS key this node should use for the given construction id.
     * @param constructionId the active construction ID
     * @param message the message to sign
     * @return the signature, using the BLS private key for the given construction ID
     */
    Bytes signWithBlsPrivateKey(long constructionId, @NonNull Bytes message);

    /**
     * Returns the BLS key pair this node should use starting with the given construction id,
     * creating the key pair if necessary.
     *
     * @param constructionId the active construction ID
     * @return the hinTS key pair
     */
    Bytes getOrCreateBlsPrivateKey(long constructionId);
}
