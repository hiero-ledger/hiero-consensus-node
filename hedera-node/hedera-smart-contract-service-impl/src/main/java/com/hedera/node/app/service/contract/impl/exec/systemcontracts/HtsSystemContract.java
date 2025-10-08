// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static com.hedera.node.app.service.token.HookDispatchUtils.HTS_HOOKS_EVM_ADDRESS;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractNativeSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

@Singleton
public class HtsSystemContract extends AbstractNativeSystemContract implements HederaSystemContract {
    public static final String HTS_SYSTEM_CONTRACT_NAME = "HTS";
    public static final String HTS_167_EVM_ADDRESS = "0x167";
    public static final String HTS_16C_EVM_ADDRESS = "0x16C";
    // The system contract ID always uses shard 0 and realm 0 so we cannot use ConversionUtils methods for this
    public static final ContractID HTS_167_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(HTS_167_EVM_ADDRESS)))
            .build();
    public static final ContractID HTS_16C_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(HTS_16C_EVM_ADDRESS)))
            .build();

    public static final Address HTS_HOOKS_CONTRACT_ADDRESS = Address.fromHexString(HTS_HOOKS_EVM_ADDRESS);

    @Inject
    public HtsSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final HtsCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(HTS_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.TOKEN);
    }

    @Override
    public FullResult computeFully(
            @NonNull ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {

        // Check if calls to the current contract address are enabled
        if (!contractsConfigOf(frame).callableHTSAddresses().contains(contractID.contractNum())) {
            return haltResult(NOT_SUPPORTED, frame.getRemainingGas());
        }

        return super.computeFully(contractID, input, frame);
    }
}
