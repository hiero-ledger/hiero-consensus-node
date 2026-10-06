// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.EthereumVerifierCallAttempt;
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
 * Translates {@code verifyConfig} calls with seed endpoints or an endpoint manifest for the Ethereum
 * verifier system contract. Same ABI shape as the Hiero TSS, Besu QBFT and Sei verifiers so a
 * single user-deployed verifier contract can dispatch to any of them without varying argument
 * lists.
 *
 * <p>Two selectors, mirroring the Hiero verifier (spec §4.8):
 * <ul>
 *   <li>{@code VERIFY_CONFIG_WITH_SEED_ENDPOINTS} (context): {@code verifyConfig(bytes,bytes32)} -> config fields +
 *       {@code seedEndpoints}. Used when {@code clpr.endpointManifestEnabled=false}.</li>
 *   <li>{@code VERIFY_CONFIG_WITH_MANIFEST} (context + manifest): {@code verifyConfig(bytes,bytes32,bytes)} ->
 *       config fields + {@code ClprEndpointManifest}. Reached when
 *       {@code clpr.endpointManifestEnabled=true}. For Ethereum the third argument is the manifest
 *       <b>raw bytes</b> (self-described at bootstrap), not a state proof.</li>
 * </ul>
 */
@Singleton
public class EthereumVerifyConfigTranslator extends AbstractCallTranslator<EthereumVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(EthereumVerifyConfigTranslator.class);

    // Seed endpoints with channel context: verifyConfig(bytes,bytes32) -> config fields + Endpoint[] seedEndpoints.
    public static final SystemContractMethod VERIFY_CONFIG_WITH_SEED_ENDPOINTS = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32)",
                    "(bytes,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64),bytes,bytes,(string,uint32,bytes,bytes)[])")
            .withCategories(Category.ETHEREUM);

    // Endpoint manifest with channel context: verifyConfig(bytes,bytes32,bytes) -> config fields +
    // ClprEndpointManifest.
    // For Ethereum the third argument is the manifest raw bytes (self-described), not a state proof.
    public static final SystemContractMethod VERIFY_CONFIG_WITH_MANIFEST = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32,bytes)", ClprVerifierAbi.VERIFY_CONFIG_WITH_MANIFEST_OUTPUTS)
            .withCategories(Category.ETHEREUM);

    @Inject
    public EthereumVerifyConfigTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.ETHEREUM_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_CONFIG_WITH_SEED_ENDPOINTS, VERIFY_CONFIG_WITH_MANIFEST);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final EthereumVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_CONFIG_WITH_MANIFEST)
                .or(() -> attempt.isMethod(VERIFY_CONFIG_WITH_SEED_ENDPOINTS));
    }

    @Override
    public Call callFrom(@NonNull final EthereumVerifierCallAttempt attempt) {
        if (attempt.isMethod(VERIFY_CONFIG_WITH_MANIFEST).isPresent()) {
            try {
                final var call = VERIFY_CONFIG_WITH_MANIFEST.decodeCall(attempt.inputBytes());
                return new EthereumVerifyConfigCall(
                        attempt.enhancement(),
                        attempt.systemContractGasCalculator(),
                        (byte[]) call.get(0),
                        (byte[]) call.get(1),
                        (byte[]) call.get(2));
            } catch (final RuntimeException e) {
                log.warn(
                        "EthereumVerifyConfigTranslator failed to decode verifyConfigWithManifest calldata: input={} bytes ({})",
                        attempt.inputBytes().length,
                        e.getMessage());
                throw e;
            }
        }
        try {
            final var call = VERIFY_CONFIG_WITH_SEED_ENDPOINTS.decodeCall(attempt.inputBytes());
            return new EthereumVerifyConfigCall(
                    attempt.enhancement(), attempt.systemContractGasCalculator(), (byte[]) call.get(0), (byte[])
                            call.get(1));
        } catch (final RuntimeException e) {
            log.warn(
                    "EthereumVerifyConfigTranslator failed to decode verifyConfigWithSeedEndpoints calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
