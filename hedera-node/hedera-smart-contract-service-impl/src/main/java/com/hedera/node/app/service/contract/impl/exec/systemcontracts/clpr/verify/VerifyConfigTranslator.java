// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
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
 * Translates {@code verifyConfig(bytes stateProofBytes) returns (bytes)} calls.
 *
 * <p>Implements the spec-defined verifier ABI for Hiero TSS at channel registration time.
 * The precompile reads the peer's trust anchor (for Hiero TSS the peer ledger_id) from the
 * {@code initial_trust_anchor} field of the {@link com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration}
 * carried inside the proof's single state-item leaf, uses it as the TSS verification key for
 * the aggregate signature in the proof's {@code signedBlockProof}, and returns the serialized
 * {@code ClprLedgerConfiguration} unchanged (no separate stamping step). A proof whose inner
 * config has an empty {@code initial_trust_anchor} is rejected.
 */
@Singleton
public class VerifyConfigTranslator extends AbstractCallTranslator<ClprCallAttempt> {

    /** ABI index for the decoded call argument. */
    static final int STATE_PROOF_INDEX = 0;

    public static final SystemContractMethod VERIFY_CONFIG =
            SystemContractMethod.declare("verifyConfig(bytes)", "(bytes)").withCategories(Category.CLPR);

    // V2 (context) — mainline: verifyConfig(bytes,bytes32) → config fields + Endpoint[] seedEndpoints.
    public static final SystemContractMethod VERIFY_CONFIG_V2 = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32)",
                    "(bytes,string,bytes,uint96,(uint64,uint64,uint64,uint64,uint64),bytes,bytes,(string,uint32,bytes,bytes)[])")
            .withCategories(Category.CLPR);

    // V3 (context + manifest) — SC-189: verifyConfig(bytes,bytes32,bytes) → config fields + ClprEndpointManifest.
    public static final SystemContractMethod VERIFY_CONFIG_V3 = SystemContractMethod.declare(
                    "verifyConfig(bytes,bytes32,bytes)", ClprVerifierAbi.VERIFY_CONFIG_V3_OUTPUTS)
            .withCategories(Category.CLPR);

    private final TssVerifier tssVerifier;

    @Inject
    public VerifyConfigTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics,
            @NonNull final TssVerifier tssVerifier) {
        super(SystemContractMethod.SystemContract.CLPR, systemContractMethodRegistry, contractMetrics);
        this.tssVerifier = tssVerifier;
        registerMethods(VERIFY_CONFIG, VERIFY_CONFIG_V2, VERIFY_CONFIG_V3);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final ClprCallAttempt attempt) {
        return attempt.isMethod(VERIFY_CONFIG_V3)
                .or(() -> attempt.isMethod(VERIFY_CONFIG_V2))
                .or(() -> attempt.isMethod(VERIFY_CONFIG));
    }

    @Override
    public Call callFrom(@NonNull final ClprCallAttempt attempt) {
        if (attempt.isMethod(VERIFY_CONFIG_V3).isPresent()) {
            final var call = VERIFY_CONFIG_V3.decodeCall(attempt.inputBytes());
            return new VerifyConfigCall(
                    attempt.enhancement(),
                    attempt.systemContractGasCalculator(),
                    call.get(0),
                    call.get(1),
                    call.get(2),
                    tssVerifier);
        }
        if (attempt.isMethod(VERIFY_CONFIG_V2).isPresent()) {
            final var call = VERIFY_CONFIG_V2.decodeCall(attempt.inputBytes());
            return new VerifyConfigCall(
                    attempt.enhancement(),
                    attempt.systemContractGasCalculator(),
                    call.get(0),
                    call.get(1),
                    tssVerifier);
        }
        final var call = VERIFY_CONFIG.decodeCall(attempt.inputBytes());
        final var stateProofBytes = (byte[]) call.get(STATE_PROOF_INDEX);
        return new VerifyConfigCall(
                attempt.enhancement(), attempt.systemContractGasCalculator(), stateProofBytes, tssVerifier);
    }
}
