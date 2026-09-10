// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.BesuQBFTVerifierCallAttempt;
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
 * Translates {@code verifyBundle(bytes bundlePayload, bytes trustAnchor) returns (bytes)} calls
 * for the Besu QBFT verifier system contract.
 */
@Singleton
public class BesuQBFTVerifyBundleTranslator extends AbstractCallTranslator<BesuQBFTVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(BesuQBFTVerifyBundleTranslator.class);

    /** ABI indices for decoded call arguments. */
    static final int BUNDLE_PAYLOAD_INDEX = 0;

    static final int TRUST_ANCHOR_INDEX = 1;

    public static final SystemContractMethod VERIFY_BUNDLE =
            SystemContractMethod.declare("verifyBundle(bytes,bytes)", "(bytes)").withCategories(Category.BESU_QBFT);

    public static final SystemContractMethod VERIFY_BUNDLE_V2 = SystemContractMethod.declare(
                    "verifyBundle(bytes,bytes,bytes)", "((uint64,bytes32,uint64,bytes32,uint8),bytes[],bytes,bytes)")
            .withCategories(Category.BESU_QBFT);

    @Inject
    public BesuQBFTVerifyBundleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.BESU_QBFT_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_BUNDLE, VERIFY_BUNDLE_V2);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final BesuQBFTVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_BUNDLE_V2).or(() -> attempt.isMethod(VERIFY_BUNDLE));
    }

    @Override
    public Call callFrom(@NonNull final BesuQBFTVerifierCallAttempt attempt) {
        if (attempt.isMethod(VERIFY_BUNDLE_V2).isPresent()) {
            try {
                final var call = VERIFY_BUNDLE_V2.decodeCall(attempt.inputBytes());
                final var bundlePayload = (byte[]) call.get(BUNDLE_PAYLOAD_INDEX);
                final var trustAnchor = (byte[]) call.get(TRUST_ANCHOR_INDEX);
                final var channelContext = (byte[]) call.get(2);
                return new BesuQBFTVerifyBundleCall(
                        attempt.enhancement(),
                        attempt.systemContractGasCalculator(),
                        bundlePayload,
                        trustAnchor,
                        channelContext);
            } catch (final RuntimeException e) {
                log.warn(
                        "BesuQBFTVerifyBundleTranslator failed to decode verifyBundle V2 calldata: input={} bytes ({})",
                        attempt.inputBytes().length,
                        e.getMessage());
                throw e;
            }
        }
        try {
            final var call = VERIFY_BUNDLE.decodeCall(attempt.inputBytes());
            final var bundlePayload = (byte[]) call.get(BUNDLE_PAYLOAD_INDEX);
            final var trustAnchor = (byte[]) call.get(TRUST_ANCHOR_INDEX);
            return new BesuQBFTVerifyBundleCall(
                    attempt.enhancement(), attempt.systemContractGasCalculator(), bundlePayload, trustAnchor);
        } catch (final RuntimeException e) {
            log.warn(
                    "BesuQBFTVerifyBundleTranslator failed to decode verifyBundle calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
