// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.EthereumVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Ethereum (consensus-layer light client) verifier service — the
 * sync-committee/BLS analog of {@link BesuQBFTVerifierSystemContract} (QBFT/MPT at {@code 0x16f})
 * and {@link SeiVerifierSystemContract} (CometBFT/IAVL at {@code 0x170}).
 */
@Singleton
public class EthereumVerifierSystemContract extends AbstractClprSystemContract implements HederaSystemContract {
    public static final String ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NAME = "EthereumVerifier";
    public static final String ETHEREUM_VERIFIER_EVM_ADDRESS = "0x171";

    @Inject
    public EthereumVerifierSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final EthereumVerifierCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(ETHEREUM_VERIFIER_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }
}
