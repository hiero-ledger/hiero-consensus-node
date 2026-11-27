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
 * Translates {@code verifyBundle(bytes bundlePayload, bytes trustAnchor, bytes channelContext)} calls for the Canton
 * verifier system contract. Same selector and return tuple as every other verifier, so {@code EvmClprVerifier}
 * dispatches to it unchanged.
 */
@Singleton
public class CantonVerifyBundleTranslator extends AbstractCallTranslator<CantonVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(CantonVerifyBundleTranslator.class);

    /** ABI indices for decoded call arguments. */
    static final int BUNDLE_PAYLOAD_INDEX = 0;

    static final int TRUST_ANCHOR_INDEX = 1;

    static final int CHANNEL_CONTEXT_INDEX = 2;

    public static final SystemContractMethod VERIFY_BUNDLE = SystemContractMethod.declare(
                    "verifyBundle(bytes,bytes,bytes)", ClprVerifierAbi.VERIFY_BUNDLE_OUTPUTS)
            .withCategories(Category.CANTON);

    @Inject
    public CantonVerifyBundleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.CANTON_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_BUNDLE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final CantonVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_BUNDLE);
    }

    @Override
    public Call callFrom(@NonNull final CantonVerifierCallAttempt attempt) {
        try {
            final var call = VERIFY_BUNDLE.decodeCall(attempt.inputBytes());
            return new CantonVerifyBundleCall(
                    attempt.enhancement(),
                    attempt.systemContractGasCalculator(),
                    (byte[]) call.get(BUNDLE_PAYLOAD_INDEX),
                    (byte[]) call.get(TRUST_ANCHOR_INDEX),
                    (byte[]) call.get(CHANNEL_CONTEXT_INDEX));
        } catch (final RuntimeException e) {
            log.warn(
                    "CantonVerifyBundleTranslator failed to decode verifyBundle calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
