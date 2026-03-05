// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.binary;

import org.hiero.base.crypto.Hash;

/**
 * A record for storing sibling hashes in a Merkle proof path.
 * <p>
 * This follows the same convention as the protobuf {@code SiblingNode.is_left} field,
 * where the boolean indicates the position of the sibling relative to the path being proven.
 * When reconstructing the Merkle tree hash, this determines whether the sibling hash
 * is used as the left or right argument in the hash combination.
 *
 * @param isLeft true if this sibling is on the left of the merkle path (meaning it will be
 *               the left argument when combining hashes), false if on the right
 * @param hash the hash of the sibling
 */
public record SiblingHash(boolean isLeft, Hash hash) {}
