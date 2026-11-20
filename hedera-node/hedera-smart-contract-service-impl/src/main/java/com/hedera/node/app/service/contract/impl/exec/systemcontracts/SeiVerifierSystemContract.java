// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Sei (CometBFT) verifier service — the CometBFT/IAVL analog of
 * {@link BesuQBFTVerifierSystemContract} (which serves QBFT/MPT at {@code 0x16f}).
 *
 * <p>Sei is not supported, so every call to {@code 0x170} halts with {@code NOT_SUPPORTED}, and neither a channel
 * nor a contract can use the Sei verifier. The implementation is kept for if Sei is supported later; supporting it
 * first requires bounding how long a verified validator set is trusted (a trusting period).
 */
@Singleton
public class SeiVerifierSystemContract extends AbstractClprSystemContract implements HederaSystemContract {
    public static final String SEI_VERIFIER_SYSTEM_CONTRACT_NAME = "SeiVerifier";
    public static final String SEI_VERIFIER_EVM_ADDRESS = "0x170";

    @Inject
    public SeiVerifierSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final SeiVerifierCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(SEI_VERIFIER_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    /**
     * Halts every invocation with {@code NOT_SUPPORTED}: the Sei verifier is not supported.
     */
    @Override
    public void computeFully(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final MessageFrame frame,
            @NonNull final Consumer<FullResult> completion) {
        requireNonNull(frame);
        completion.accept(haltResult(NOT_SUPPORTED, frame.getRemainingGas()));
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }
}
