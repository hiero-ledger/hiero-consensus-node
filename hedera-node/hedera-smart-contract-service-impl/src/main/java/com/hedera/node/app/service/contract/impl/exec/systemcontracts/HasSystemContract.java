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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

@Singleton
public class HasSystemContract extends AbstractNativeSystemContract implements HederaSystemContract {
    public static final String HAS_SYSTEM_CONTRACT_NAME = "HAS";
    public static final String HAS_EVM_ADDRESS = "0x16a";
    // The system contract ID always uses shard 0 and realm 0 so we cannot use ConversionUtils methods for this
    public static final ContractID HAS_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(HAS_EVM_ADDRESS)))
            .build();

    @Inject
    public HasSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final HasCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics,
            @NonNull final HederaOpsDuration hederaOpsDuration) {
        super(HAS_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics, hederaOpsDuration);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }

    @Override
    public FullResult computeFully(
            @NonNull ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);

        // Check if calls to hedera account service is enabled
        if (!contractsConfigOf(frame).systemContractAccountServiceEnabled()) {
            return haltResult(NOT_SUPPORTED, frame.getRemainingGas());
        }

        return super.computeFully(contractID, input, frame);
    }
}
