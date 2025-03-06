// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractNativeSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 * System contract for the Hedera Schedule Service (HSS) system contract.
 */
@Singleton
public class HssSystemContract extends AbstractNativeSystemContract implements HederaSystemContract {
    public static final String HSS_SYSTEM_CONTRACT_NAME = "HSS";
    public static final String HSS_EVM_ADDRESS = "0x16b";
    // The system contract ID always uses shard 0 and realm 0 so we cannot use ConversionUtils methods for this
    public static final ContractID HSS_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(HSS_EVM_ADDRESS)))
            .build();

    @Inject
    public HssSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final HssCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(HSS_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.SCHEDULE_TXN);
    }

    @Override
    public FullResult computeFully(
            @NonNull ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);

        // Check if calls to hedera schedule service is enabled
        if (!contractsConfigOf(frame).systemContractScheduleServiceEnabled()) {
            return haltResult(NOT_SUPPORTED, frame.getRemainingGas());
        }

        return super.computeFully(contractID, input, frame);
    }
}
