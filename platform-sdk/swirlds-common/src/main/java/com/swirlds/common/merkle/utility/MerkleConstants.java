// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import com.swirlds.common.crypto.DigestType;

/**
 * Class with constants used for Merkle
 */
public abstract class MerkleConstants {

    /**
     * The digest type used to compute hashes of merkle trees.
     */
    public static final DigestType MERKLE_DIGEST_TYPE = DigestType.SHA_384;
}
