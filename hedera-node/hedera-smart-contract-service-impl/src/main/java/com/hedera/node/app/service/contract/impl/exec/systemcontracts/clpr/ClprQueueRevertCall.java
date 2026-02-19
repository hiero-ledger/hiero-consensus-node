// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * A CLPR queue call that always reverts with a typed reason.
 */
public class ClprQueueRevertCall extends AbstractCall {
    private final Bytes reason;

    public ClprQueueRevertCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final String reason) {
        super(gasCalculator, enhancement, false);
        this.reason = Bytes.wrap(requireNonNull(reason).getBytes(UTF_8));
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        return gasOnly(revertResult(reason, gasCalculator.viewGasRequirement()), CONTRACT_REVERT_EXECUTED, isViewCall);
    }
}
