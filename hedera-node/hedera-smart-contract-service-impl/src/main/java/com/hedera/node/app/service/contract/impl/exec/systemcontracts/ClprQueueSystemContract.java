// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractNativeSystemContract;
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
 * System contract for CLPR queue adapter methods.
 */
@Singleton
public class ClprQueueSystemContract extends AbstractNativeSystemContract implements HederaSystemContract {
    public static final String CLPR_QUEUE_SYSTEM_CONTRACT_NAME = "CLPR_QUEUE";
    public static final String CLPR_QUEUE_EVM_ADDRESS = "0x16e";
    public static final ContractID CLPR_QUEUE_CONTRACT_ID = ContractID.newBuilder()
            .contractNum(numberOfLongZero(Address.fromHexString(CLPR_QUEUE_EVM_ADDRESS)))
            .build();
    private static final Bytes DISABLED_REASON = Bytes.wrap("CLPR_QUEUE_DISABLED".getBytes(UTF_8));

    @Inject
    public ClprQueueSystemContract(
            @NonNull final GasCalculator gasCalculator,
            @NonNull final ClprQueueCallFactory callFactory,
            @NonNull final ContractMetrics contractMetrics) {
        super(CLPR_QUEUE_SYSTEM_CONTRACT_NAME, callFactory, gasCalculator, contractMetrics);
    }

    @Override
    protected FrameUtils.CallType callTypeOf(@NonNull final MessageFrame frame) {
        return FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT);
    }

    @Override
    public FullResult computeFully(
            @NonNull final ContractID contractID, @NonNull final Bytes input, @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);
        if (!contractsConfigOf(frame).systemContractClprQueueEnabled()) {
            return revertResult(DISABLED_REASON, frame.getRemainingGas());
        }
        return super.computeFully(contractID, input, frame);
    }
}
