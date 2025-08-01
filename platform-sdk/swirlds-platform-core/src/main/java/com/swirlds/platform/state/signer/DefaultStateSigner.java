// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signer;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.crypto.PlatformSigner;

/**
 * A standard implementation of a {@link StateSigner}.
 */
public class DefaultStateSigner implements StateSigner {
    private static final Logger logger = LogManager.getLogger(DefaultStateSigner.class);

    /**
     * An object responsible for signing states with this node's key.
     */
    private final PlatformSigner signer;

    /**
     * Constructor.
     *
     * @param signer      an object responsible for signing states with this node's key
     */
    public DefaultStateSigner(@NonNull final PlatformSigner signer) {
        this.signer = Objects.requireNonNull(signer);
    }

    /**
     * Sign the given state and produce a {@link StateSignatureTransaction} containing the signature. This method assumes
     * that the given {@link ReservedSignedState} is reserved by the caller and will release the state when done.
     *
     * @param reservedSignedState the state to sign
     * @return a {@link StateSignatureTransaction} containing the signature, or null if the state should not be signed
     */
    @Override
    @Nullable
    public StateSignatureTransaction signState(@NonNull final ReservedSignedState reservedSignedState) {
        try (reservedSignedState) {
            if (reservedSignedState.get().isPcesRound()) {
                // don't sign states produced during PCES replay
                return null;
            }

            final Hash stateHash =
                    Objects.requireNonNull(reservedSignedState.get().getState().getHash());
            final Bytes signature = signer.signImmutable(stateHash);
            Objects.requireNonNull(signature);

            final StateSignatureTransaction signatureTransaction = StateSignatureTransaction.newBuilder()
                    .round(reservedSignedState.get().getRound())
                    .signature(signature)
                    .hash(stateHash.getBytes())
                    .build();
            logger.info(
                    LogMarker.STARTUP.getMarker(),
                    "DefaultStateSigner.signState(), round {}",
                    signatureTransaction.round());
            return signatureTransaction;
        }
    }
}
