// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_VERIFIER_CONTRACT;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Resolves the appropriate {@link ClprVerifier} for a Channel's verifier contract.
 *
 * <p>Every Channel specifies a {@code verifier_contract} at registration time and that
 * contract is always a deployed EVM smart contract. The factory returns an
 * {@link EvmClprVerifier} that dispatches verification calls to that specific contract.
 * The contract must implement:
 * <pre>
 *   function verifyConfig(bytes calldata proofBytes) external returns (bytes memory);
 *   function verifyBundle(bytes calldata bundlePayload, bytes calldata trustAnchor)
 *       external returns (bytes memory);
 * </pre>
 *
 * <p>Heavy lifting (TSS signature verification, Merkle path walking) is available to the
 * verifier contract through the CLPR system contract precompiles
 * ({@code verifyConfig}/{@code verifyBundle} on the precompile at {@code 0x16e}) — see
 * {@code com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify}.
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
        // TODO(CLPR-5.3): Check against a registry of built-in verifiers first; return one if found.
        if (!verifierContract.hasContractNum() && !verifierContract.hasEvmAddress()) {
            throw new HandleException(CLPR_INVALID_VERIFIER_CONTRACT);
        }
        return new EvmClprVerifier(verifierContract);
    }
}
