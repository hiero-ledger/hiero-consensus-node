// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.crypto.Hash;

/**
 * A record that wraps a reserved signed state and its hash.
 *
 * @param state the reserved signed state
 * @param hash the hash of the state
 */
public record HashedReservedSignedState(@NonNull ReservedSignedState state, @NonNull Hash hash) {
    public HashedReservedSignedState {
        requireNonNull(state);
        requireNonNull(hash);
    }
}
