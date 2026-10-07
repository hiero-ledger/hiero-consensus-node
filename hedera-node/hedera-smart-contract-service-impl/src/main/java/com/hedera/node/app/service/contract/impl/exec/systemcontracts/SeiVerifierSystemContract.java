// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Sei (CometBFT) verifier service — the CometBFT/IAVL analog of
 * {@link BesuQBFTVerifierSystemContract} (which serves QBFT/MPT at {@code 0x16f}).
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

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }
}
