// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.common.output;

import com.swirlds.common.crypto.Signature;
import org.hiero.consensus.model.crypto.Hash;

/**
 * A node's signature for a round's state hash.
 *
 * @param round
 * 		the round of the state this signature applies to
 * @param signerId
 * 		the id of the signing node
 * @param stateHash
 * 		the hash of the state that is signed
 * @param signature
 * 		the signature of the stateHash
 */
public record StateSignature(long round, long signerId, Hash stateHash, Signature signature) {}
