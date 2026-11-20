// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Registry of the built-in verifiers that are implemented natively, rather than as an EVM contract. Any contract
 * not found here is an EVM verifier, which {@link ClprVerifierFactory} handles with an {@link EvmClprVerifier}.
 */
public final class VerifiersRegistry {
    /** Canton verifier reserved contract num (0x172). */
    public static final long CANTON_CONTRACT_NUM = 0x172L;

    /** EVM-address form of {@link #CANTON_CONTRACT_NUM}. */
    public static final Bytes CANTON_EVM_ADDRESS =
            Bytes.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01, (byte) 0x72});

    /** The Canton verifier is stateless, so one instance serves every call. */
    private static final CantonProofVerifier CANTON_VERIFIER = new CantonProofVerifier();

    private VerifiersRegistry() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Finds the native verifier registered for the given contract.
     *
     * @param verifierContract the Channel's verifier contract, by number or EVM address
     * @return the native verifier, or empty if the contract is not a native built-in
     */
    @NonNull
    public static Optional<ClprVerifier> find(@NonNull final ContractID verifierContract) {
        final boolean isCanton = verifierContract.hasContractNum()
                ? verifierContract.contractNumOrElse(0L) == CANTON_CONTRACT_NUM
                : CANTON_EVM_ADDRESS.equals(verifierContract.evmAddressOrElse(Bytes.EMPTY));
        return isCanton ? Optional.of(CANTON_VERIFIER) : Optional.empty();
    }
}
