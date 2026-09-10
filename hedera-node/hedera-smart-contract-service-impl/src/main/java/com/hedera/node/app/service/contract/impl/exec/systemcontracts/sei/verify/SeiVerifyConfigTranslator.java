// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallAttempt;
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
 * Translates {@code verifyConfig(bytes configPayload) returns (bytes)} calls for the Sei
 * verifier system contract. Same ABI shape as the Hiero TSS and Besu QBFT verifiers so a single
 * user-deployed verifier contract can dispatch to any of them without varying argument lists.
 */
@Singleton
public class SeiVerifyConfigTranslator extends AbstractCallTranslator<SeiVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(SeiVerifyConfigTranslator.class);

    /** ABI index of the sole call argument. */
    static final int CONFIG_PAYLOAD_INDEX = 0;

    public static final SystemContractMethod VERIFY_CONFIG =
            SystemContractMethod.declare("verifyConfig(bytes)", "(bytes)").withCategories(Category.SEI);

    // V2 (context): verifyConfig(bytes,bytes32) -> config fields + Endpoint[] seedEndpoints.
    public static final SystemContractMethod VERIFY_CONFIG_V2 = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32)",
                    "(bytes,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64),bytes,bytes,(string,uint32,bytes,bytes)[])")
            .withCategories(Category.SEI);

    // V3 (context + manifest): verifyConfig(bytes,bytes32,bytes) -> config fields + ClprEndpointManifest.
    public static final SystemContractMethod VERIFY_CONFIG_V3 = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32,bytes)", ClprVerifierAbi.VERIFY_CONFIG_V3_OUTPUTS)
            .withCategories(Category.SEI);

    @Inject
    public SeiVerifyConfigTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.SEI_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_CONFIG, VERIFY_CONFIG_V2, VERIFY_CONFIG_V3);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final SeiVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_CONFIG_V3)
                .or(() -> attempt.isMethod(VERIFY_CONFIG_V2))
                .or(() -> attempt.isMethod(VERIFY_CONFIG));
    }

    @Override
    public Call callFrom(@NonNull final SeiVerifierCallAttempt attempt) {
        if (attempt.isMethod(VERIFY_CONFIG_V3).isPresent()) {
            try {
                final var call = VERIFY_CONFIG_V3.decodeCall(attempt.inputBytes());
                return new SeiVerifyConfigCall(
                        attempt.enhancement(),
                        attempt.systemContractGasCalculator(),
                        (byte[]) call.get(0),
                        (byte[]) call.get(1),
                        (byte[]) call.get(2));
            } catch (final RuntimeException e) {
                log.warn(
                        "SeiVerifyConfigTranslator failed to decode verifyConfig V3 calldata: input={} bytes ({})",
                        attempt.inputBytes().length,
                        e.getMessage());
                throw e;
            }
        }
        if (attempt.isMethod(VERIFY_CONFIG_V2).isPresent()) {
            try {
                final var call = VERIFY_CONFIG_V2.decodeCall(attempt.inputBytes());
                return new SeiVerifyConfigCall(
                        attempt.enhancement(), attempt.systemContractGasCalculator(), (byte[]) call.get(0), (byte[])
                                call.get(1));
            } catch (final RuntimeException e) {
                log.warn(
                        "SeiVerifyConfigTranslator failed to decode verifyConfig V2 calldata: input={} bytes ({})",
                        attempt.inputBytes().length,
                        e.getMessage());
                throw e;
            }
        }
        try {
            final var call = VERIFY_CONFIG.decodeCall(attempt.inputBytes());
            final var configPayload = (byte[]) call.get(CONFIG_PAYLOAD_INDEX);
            return new SeiVerifyConfigCall(attempt.enhancement(), attempt.systemContractGasCalculator(), configPayload);
        } catch (final RuntimeException e) {
            log.warn(
                    "SeiVerifyConfigTranslator failed to decode verifyConfig calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
