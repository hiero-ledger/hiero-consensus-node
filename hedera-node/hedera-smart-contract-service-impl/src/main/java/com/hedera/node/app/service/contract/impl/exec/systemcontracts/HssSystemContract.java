/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asNumberedContractId;
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
    public static final ContractID HSS_CONTRACT_ID = asNumberedContractId(Address.fromHexString(HSS_EVM_ADDRESS));

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
