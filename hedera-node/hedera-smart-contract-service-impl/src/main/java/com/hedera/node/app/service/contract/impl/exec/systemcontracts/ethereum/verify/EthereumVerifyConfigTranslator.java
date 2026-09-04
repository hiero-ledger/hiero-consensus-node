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
 * Translates {@code verifyConfig(bytes configPayload) returns (bytes)} calls for the Ethereum
 * verifier system contract. Same ABI shape as the Hiero TSS, Besu QBFT and Sei verifiers so a
 * single user-deployed verifier contract can dispatch to any of them without varying argument
 * lists.
 *
 * <p>Three selectors, mirroring the Hiero verifier (spec §4.8):
 * <ul>
 *   <li>{@code VERIFY_CONFIG} (V1, legacy): {@code verifyConfig(bytes) -> (bytes)}. Reached when
 *       {@code clpr.endpointManifestEnabled=false}. Returns the proven config bytes only.</li>
 *   <li>{@code VERIFY_CONFIG_V2} (context): {@code verifyConfig(bytes,bytes32)} -> config fields +
 *       {@code seedEndpoints}. Binds the returned config to a channel context.</li>
 *   <li>{@code VERIFY_CONFIG_V3} (context + manifest): {@code verifyConfig(bytes,bytes32,bytes)} ->
 *       config fields + {@code ClprEndpointManifest}. Reached when
 *       {@code clpr.endpointManifestEnabled=true}. For Ethereum the third argument is the manifest
 *       <b>raw bytes</b> (self-described at bootstrap), not a state proof.</li>
 * </ul>
 */
@Singleton
public class EthereumVerifyConfigTranslator extends AbstractCallTranslator<EthereumVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(EthereumVerifyConfigTranslator.class);

    /** ABI index of the sole call argument. */
    static final int CONFIG_PAYLOAD_INDEX = 0;

    public static final SystemContractMethod VERIFY_CONFIG =
            SystemContractMethod.declare("verifyConfig(bytes)", "(bytes)").withCategories(Category.ETHEREUM);

    // V2 (context): verifyConfig(bytes,bytes32) -> config fields + Endpoint[] seedEndpoints.
    public static final SystemContractMethod VERIFY_CONFIG_V2 = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32)",
                    "(bytes,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64),bytes,bytes,(string,uint32,bytes,bytes)[])")
            .withCategories(Category.ETHEREUM);

    // V3 (context + manifest): verifyConfig(bytes,bytes32,bytes) -> config fields + ClprEndpointManifest.
    // For Ethereum the third argument is the manifest raw bytes (self-described), not a state proof.
    public static final SystemContractMethod VERIFY_CONFIG_V3 = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32,bytes)", ClprVerifierAbi.VERIFY_CONFIG_V3_OUTPUTS)
            .withCategories(Category.ETHEREUM);

    @Inject
    public EthereumVerifyConfigTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.ETHEREUM_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_CONFIG, VERIFY_CONFIG_V2, VERIFY_CONFIG_V3);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final EthereumVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_CONFIG_V3)
                .or(() -> attempt.isMethod(VERIFY_CONFIG_V2))
                .or(() -> attempt.isMethod(VERIFY_CONFIG));
    }

    @Override
    public Call callFrom(@NonNull final EthereumVerifierCallAttempt attempt) {
        if (attempt.isMethod(VERIFY_CONFIG_V3).isPresent()) {
            try {
                final var call = VERIFY_CONFIG_V3.decodeCall(attempt.inputBytes());
                return new EthereumVerifyConfigCall(
                        attempt.enhancement(),
                        attempt.systemContractGasCalculator(),
                        (byte[]) call.get(0),
                        (byte[]) call.get(1),
                        (byte[]) call.get(2));
            } catch (final RuntimeException e) {
                log.warn(
                        "EthereumVerifyConfigTranslator failed to decode verifyConfig V3 calldata: input={} bytes ({})",
                        attempt.inputBytes().length,
                        e.getMessage());
                throw e;
            }
        }
        if (attempt.isMethod(VERIFY_CONFIG_V2).isPresent()) {
            try {
                final var call = VERIFY_CONFIG_V2.decodeCall(attempt.inputBytes());
                return new EthereumVerifyConfigCall(
                        attempt.enhancement(), attempt.systemContractGasCalculator(), (byte[]) call.get(0), (byte[])
                                call.get(1));
            } catch (final RuntimeException e) {
                log.warn(
                        "EthereumVerifyConfigTranslator failed to decode verifyConfig V2 calldata: input={} bytes ({})",
                        attempt.inputBytes().length,
                        e.getMessage());
                throw e;
            }
        }
        try {
            final var call = VERIFY_CONFIG.decodeCall(attempt.inputBytes());
            final var configPayload = (byte[]) call.get(CONFIG_PAYLOAD_INDEX);
            return new EthereumVerifyConfigCall(
                    attempt.enhancement(), attempt.systemContractGasCalculator(), configPayload);
        } catch (final RuntimeException e) {
            log.warn(
                    "EthereumVerifyConfigTranslator failed to decode verifyConfig calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
