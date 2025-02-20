// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.Signature;

/**
 * Capable of signing a {@link Hash}
 */
@FunctionalInterface
public interface HashSigner {
    /**
     * @param hash
     * 		the hash to sign
     * @return the signature for the hash provided
     */
    Signature sign(Hash hash);
}
