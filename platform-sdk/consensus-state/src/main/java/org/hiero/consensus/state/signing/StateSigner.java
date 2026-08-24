// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.signing;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.wiring.framework.component.InputWireLabel;

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
    @InputWireLabel("hashed states")
    @Nullable
    StateSignatureTransaction signState(@NonNull ReservedSignedState reservedSignedState);
}
