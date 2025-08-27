// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signer;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * This component is responsible for signing states and producing {@link StateSignatureTransaction}s.
 */
public interface StateSigner {

    /**
     * Sign the given state and produce a {@link StateSignatureTransaction} containing the signature. Will not sign any
     * state produced during PCES replay.
     *
     * @param reservedSignedState the state to sign
     * @return a {@link StateSignatureTransaction} containing the signature, or null if the state should not be signed
     */
    @InputWireLabel("hashed states to sign")
    @Nullable
    StateSignatureTransaction signState(@NonNull ReservedSignedState reservedSignedState);
}
