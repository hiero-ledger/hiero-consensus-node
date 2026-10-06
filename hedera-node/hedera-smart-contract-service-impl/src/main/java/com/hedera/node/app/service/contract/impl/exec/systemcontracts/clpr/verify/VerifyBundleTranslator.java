// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code verifyBundle(bytes bundlePayload, bytes trustAnchor, bytes channelContext)} calls.
 *
 * <p>Implements the spec-defined verifier ABI for Hiero TSS: verifies each Merkle path against
 * the proof's block-root signature using {@code trustAnchor} as the peer ledger_id, accumulates
 * the channel metadata + message payloads, and returns an ABI tuple containing the verified bundle content
 * (including a {@code new_trust_anchor} / {@code new_trust_anchor_id} successor pair when the
 * source ledger has signalled an in-band ledger-ID succession).
 */
@Singleton
public class VerifyBundleTranslator extends AbstractCallTranslator<ClprCallAttempt> {

    /** ABI indices for decoded call arguments. */
    static final int BUNDLE_PAYLOAD_INDEX = 0;

    static final int TRUST_ANCHOR_INDEX = 1;

    public static final SystemContractMethod VERIFY_BUNDLE = SystemContractMethod.declare(
                    "verifyBundle(bytes,bytes,bytes)", "((uint64,bytes32,uint64,bytes32,uint8),bytes[],bytes,bytes)")
            .withCategories(Category.CLPR);

    private final TssVerifier tssVerifier;

    @Inject
    public VerifyBundleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics,
            @NonNull final TssVerifier tssVerifier) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        this.tssVerifier = tssVerifier;
        registerMethods(VERIFY_BUNDLE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final ClprCallAttempt attempt) {
        return attempt.isMethod(VERIFY_BUNDLE);
    }

    @Override
    public Call callFrom(@NonNull final ClprCallAttempt attempt) {
        final var call = VERIFY_BUNDLE.decodeCall(attempt.inputBytes());
        final var bundlePayload = (byte[]) call.get(BUNDLE_PAYLOAD_INDEX);
        final var trustAnchor = (byte[]) call.get(TRUST_ANCHOR_INDEX);
        return new VerifyBundleCall(
                attempt.enhancement(), attempt.systemContractGasCalculator(), bundlePayload, trustAnchor, tssVerifier);
    }
}
