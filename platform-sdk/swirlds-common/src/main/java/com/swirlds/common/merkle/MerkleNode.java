// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle;

/**
 * A MerkleNode object has the following properties
 * <ul>
 *     <li>Doesn't need to compute its hash</li>
 *     <li>It's not aware of Cryptographic Modules</li>
 *     <li>Doesn't need to perform rsync</li>
 *     <li>Doesn't need to provide hints to the Crypto Module</li>
 * </ul>
 */
public interface MerkleNode {}
