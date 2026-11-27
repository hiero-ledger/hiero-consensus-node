// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.CantonVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Canton verifier, the Canton (mediator verdict / Merkle view) analog of
 * {@link EthereumVerifierSystemContract} ({@code 0x171}) and {@link BesuQBFTVerifierSystemContract} ({@code 0x16f}).
 * Channels that name {@code 0x173} as their verifier reach it through the CLPR service's {@code EvmClprVerifier},
 * like every other built-in verifier, both at consensus and in local bundle prevalidation.
 */
@Singleton
public class CantonVerifierSystemContract extends AbstractClprSystemContract implements HederaSystemContract {
    public static final String CANTON_VERIFIER_SYSTEM_CONTRACT_NAME = "CantonVerifier";
    public static final String CANTON_VERIFIER_EVM_ADDRESS = "0x173";

    @Inject
    public CantonVerifierSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final CantonVerifierCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(CANTON_VERIFIER_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }
}
