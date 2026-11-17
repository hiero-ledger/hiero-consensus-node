// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.BesuQBFTVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractClprSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Besu QBFT verifier service.
 */
@Singleton
public class BesuQBFTVerifierSystemContract extends AbstractClprSystemContract implements HederaSystemContract {
    public static final String BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NAME = "BesuQBFTVerifier";
    public static final String BESU_QBFT_VERIFIER_EVM_ADDRESS = "0x16f";
    public static final ContractID BESU_QBFT_VERIFIER_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(BESU_QBFT_VERIFIER_EVM_ADDRESS)))
            .build();

    @Inject
    public BesuQBFTVerifierSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final BesuQBFTVerifierCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(BESU_QBFT_VERIFIER_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }
}
