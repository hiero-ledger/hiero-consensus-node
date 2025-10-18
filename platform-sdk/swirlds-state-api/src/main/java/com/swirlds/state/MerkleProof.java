// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;

/**
 * Represents a Merkle proof containing all necessary information to verify a state item.
 *
 * @param stateItem        byte representation of {@code StateItem}
 * @param siblingHashes     a list of sibling hashes used in the Merkle proof from the leaf of {@code stateItem} to the root of the state
 * @param innerParentHashes a list of byte arrays representing inner parent hashes, where:
 *                          <ul>
 *                              <li><code>innerParentHashes.get(0)</code> is the hash of the Merkle leaf
 *                                  or the state item, depending on which is used.</li>
 *                              <li><code>innerParentHashes.get(n+1)</code> is computed as
 *                                  <code>Hash(innerParentHashes.get(n) [+] siblingHashes.get(n).hash())</code>,
 *                                  where the order of concatenation around <code>[+]</code> may be swapped
 *                                  depending on whether the sibling hash is a left or right child.</li>
 *                          </ul>
 */
public record MerkleProof(Bytes stateItem, List<SiblingHash> siblingHashes, List<byte[]> innerParentHashes) {}
