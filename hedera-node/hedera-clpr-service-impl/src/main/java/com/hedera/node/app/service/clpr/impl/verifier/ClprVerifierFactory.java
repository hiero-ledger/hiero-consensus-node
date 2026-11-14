// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteChannelHandler;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Resolves the appropriate {@link ClprVerifier} for a Channel's verifier contract.
 *
 * <p>Resolution is registry-first: if the contract is a known built-in (see
 * {@link ClprCompleteChannelHandler#builtInVerifierFor}), that verifier is returned directly.
 * Built-ins include the four EVM precompile-backed verifiers (hiero-tss/besu-qbft/sei-cometbft/
 * ethereum-sync-committee, {@code 0x16e–0x171}) routed via {@link EvmClprVerifier}, and the
 * native Canton verifier ({@code 0x172}) routed via {@code CantonProofVerifier}. Any other
 * contract (a custom deployed EVM verifier) falls through to a fresh {@link EvmClprVerifier}.
 *
 * <p>Heavy lifting (TSS signature verification, Merkle path walking) is available to EVM
 * verifier contracts through the CLPR system contract precompiles
 * ({@code verifyConfig}/{@code verifyBundle} on the precompile at {@code 0x16e}) — see
 * {@code com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify}.
 *
 * <p>Note: this class depends on {@link ClprCompleteChannelHandler} (a static call), which is
 * the reverse of the existing handler→factory injection direction. No Dagger cycle arises because
 * the dependency is a static method, not an injected field.
 */
@Singleton
public class ClprVerifierFactory {

    @Inject
    public ClprVerifierFactory() {
        // Exists for Dagger injection
    }

    /**
     * Returns the verifier for the given verifier contract.
     *
     * @param verifierContract the Channel's verifier contract
     * @return the appropriate verifier implementation, never {@code null}
     * @throws HandleException with {@code CLPR_INVALID_VERIFIER_CONTRACT} if the contract
     *     cannot be resolved to a verifier
     */
    @NonNull
    public ClprVerifier getVerifier(@NonNull final ContractID verifierContract) throws HandleException {
        requireNonNull(verifierContract);
        if (!verifierContract.hasContractNum() && !verifierContract.hasEvmAddress()) {
            throw new HandleException(CLPR_INVALID_VERIFIER_CONTRACT);
        }
        final var builtIn = ClprCompleteChannelHandler.builtInVerifierFor(verifierContract);
        return builtIn != null ? builtIn : new EvmClprVerifier(verifierContract);
    }
}
