// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.base.crypto.Hash;

/**
 * Represents a Merkle proof containing all necessary information to verify a state item.
 *
 * @param stateItem        byte representation of {@code StateItem}, the item this proof is for
 * @param siblingHashes     a list of sibling hashes used in the Merkle proof from the leaf of {@code stateItem} to the root of the state
 * @param innerParentHashes a list of byte arrays representing inner parent hashes, where:
 *                          <ul>
 *                              <li><code>innerParentHashes.get(0)</code> is the hash of the Merkle leaf
 *                              <li><code>innerParentHashes.get(1)</code> is a hash of a parent</li>
 *                              <li><code>innerParentHashes.get(2)</code> is a hash of a grandparent</li>
 *                              <li>and so on</li>
 *                          </ul>
 */
public record MerkleProof(Bytes stateItem, List<SiblingHash> siblingHashes, List<Hash> innerParentHashes) {}
