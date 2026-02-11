// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import org.hiero.base.crypto.Hash;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This record encapsulates the data read from a new signed state.
 *
 * @param reservedSignedState
 * 		the signed state that was loaded
 * @param originalHash
 * 		the hash of the signed state when it was serialized, may not be the same as the current hash
 */
public record DeserializedSignedState(ReservedSignedState reservedSignedState, Hash originalHash) {}
