// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify;

import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.CantonVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Translates {@code verifyConfig(bytes configProof, bytes32 channelId, bytes endpointManifestProof)} calls for the
 * Canton verifier system contract. Same selector and return tuple as the Hiero TSS, Besu QBFT and Ethereum verifiers,
 * so {@code EvmClprVerifier} dispatches to it unchanged.
 */
@Singleton
public class CantonVerifyConfigTranslator extends AbstractCallTranslator<CantonVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(CantonVerifyConfigTranslator.class);

    public static final SystemContractMethod VERIFY_CONFIG = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32,bytes)", ClprVerifierAbi.VERIFY_CONFIG_OUTPUTS)
            .withCategories(Category.CANTON);

    @Inject
    public CantonVerifyConfigTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CANTON_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_CONFIG);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final CantonVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_CONFIG);
    }

    @Override
    public Call callFrom(@NonNull final CantonVerifierCallAttempt attempt) {
        try {
            final var call = VERIFY_CONFIG.decodeCall(attempt.inputBytes());
            return new CantonVerifyConfigCall(
                    attempt.enhancement(),
                    attempt.systemContractGasCalculator(),
                    (byte[]) call.get(0),
                    (byte[]) call.get(1),
                    (byte[]) call.get(2));
        } catch (final RuntimeException e) {
            log.warn(
                    "CantonVerifyConfigTranslator failed to decode verifyConfig calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
