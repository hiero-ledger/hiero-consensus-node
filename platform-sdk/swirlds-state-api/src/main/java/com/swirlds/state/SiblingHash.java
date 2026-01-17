// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import org.hiero.base.crypto.Hash;

/**
 * A record for storing sibling hashes.
 * @param isRight true if this is a right sibling, false if this is a left sibling.
 * @param hash the hash of the sibling.
 */
public record SiblingHash(boolean isRight, Hash hash) {}
