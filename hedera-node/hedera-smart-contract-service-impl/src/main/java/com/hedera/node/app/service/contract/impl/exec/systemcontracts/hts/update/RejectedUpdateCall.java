// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * A token update call whose input was decoded but cannot be executed as requested. It reverts with the
 * given status and charges the canonical gas of a token update, without dispatching anything. It has no
 * schedulable form; {@code scheduleNative()} reverts with the same result instead of scheduling it.
 */
public class RejectedUpdateCall extends AbstractCall {
    private final ResponseCodeEnum status;

    /**
     * @param attempt the attempt that was rejected
     * @param status the status to revert with
     */
    public RejectedUpdateCall(@NonNull final HtsCallAttempt attempt, @NonNull final ResponseCodeEnum status) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.status = requireNonNull(status);
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        return reversionWith(status, gasCalculator.canonicalGasRequirement(DispatchType.UPDATE));
    }
}
