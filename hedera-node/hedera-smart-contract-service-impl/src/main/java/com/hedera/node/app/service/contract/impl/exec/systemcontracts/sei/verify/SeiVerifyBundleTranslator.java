// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

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
 * Translates {@code verifyBundle(bytes bundlePayload, bytes trustAnchor, bytes channelContext)} calls
 * for the Sei verifier system contract.
 */
@Singleton
public class SeiVerifyBundleTranslator extends AbstractCallTranslator<SeiVerifierCallAttempt> {
    private static final Logger log = LogManager.getLogger(SeiVerifyBundleTranslator.class);

    /** ABI indices for decoded call arguments. */
    static final int BUNDLE_PAYLOAD_INDEX = 0;

    static final int TRUST_ANCHOR_INDEX = 1;

    public static final SystemContractMethod VERIFY_BUNDLE = SystemContractMethod.declare(
                    "verifyBundle(bytes,bytes,bytes)", "((uint64,bytes32,uint64,bytes32,uint8),bytes[],bytes,bytes)")
            .withCategories(Category.SEI);

    @Inject
    public SeiVerifyBundleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.SEI_VERIFIER, systemContractMethodRegistry, contractMetrics);
        registerMethods(VERIFY_BUNDLE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final SeiVerifierCallAttempt attempt) {
        return attempt.isMethod(VERIFY_BUNDLE);
    }

    @Override
    public Call callFrom(@NonNull final SeiVerifierCallAttempt attempt) {
        try {
            final var call = VERIFY_BUNDLE.decodeCall(attempt.inputBytes());
            final var bundlePayload = (byte[]) call.get(BUNDLE_PAYLOAD_INDEX);
            final var trustAnchor = (byte[]) call.get(TRUST_ANCHOR_INDEX);
            final var channelContext = (byte[]) call.get(2);
            return new SeiVerifyBundleCall(
                    attempt.enhancement(),
                    attempt.systemContractGasCalculator(),
                    bundlePayload,
                    trustAnchor,
                    channelContext);
        } catch (final RuntimeException e) {
            log.warn(
                    "SeiVerifyBundleTranslator failed to decode verifyBundle calldata: input={} bytes ({})",
                    attempt.inputBytes().length,
                    e.getMessage());
            throw e;
        }
    }
}
