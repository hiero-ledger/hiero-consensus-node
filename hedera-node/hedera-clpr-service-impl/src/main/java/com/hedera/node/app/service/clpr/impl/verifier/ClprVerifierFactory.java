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
 * <p>Every verifier contract gets an {@link EvmClprVerifier} that dispatches verification calls to that specific
 * contract: a deployed EVM smart contract, or one of the system-contract built-ins ({@code 0x16e} Hiero TSS,
 * {@code 0x16f} Besu QBFT, {@code 0x171} Ethereum sync committee, {@code 0x173} Canton). There are no native
 * verifiers, so consensus verification and local prevalidation always share one code path. The contract must implement the config ABI with
 * seed endpoints, or the manifest-aware config ABI when endpoint manifests are enabled, and the three-argument
 * bundle ABI described by {@link EvmClprVerifier}.
 *
 * <p>Heavy lifting (TSS signature verification, Merkle path walking) is available to EVM
 * verifier contracts through the CLPR system contract precompiles
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
        if (!verifierContract.hasContractNum() && !verifierContract.hasEvmAddress()) {
            throw new HandleException(CLPR_INVALID_VERIFIER_CONTRACT);
        }
        return new EvmClprVerifier(verifierContract);
    }
}
