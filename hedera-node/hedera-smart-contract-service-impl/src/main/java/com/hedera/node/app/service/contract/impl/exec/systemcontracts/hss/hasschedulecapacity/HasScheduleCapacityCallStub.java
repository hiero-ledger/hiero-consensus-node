// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.hasschedulecapacity;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * An HSS call that simply dispatches a synthetic transaction body and returns a result that is an encoded
 * {@link ResponseCodeEnum}.
 */
public class HasScheduleCapacityCallStub extends AbstractCall {

    public HasScheduleCapacityCallStub(@NonNull final HssCallAttempt attempt) {
        this(attempt.enhancement(), attempt.systemContractGasCalculator());
    }

    public HasScheduleCapacityCallStub(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator) {
        super(gasCalculator, enhancement, true);
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        return gasOnly(
                successResult(
                        HasScheduleCapacityTranslator.HAS_SCHEDULE_CAPACITY
                                .getOutputs()
                                .encode(Tuple.singleton(true)),
                        gasCalculator.viewGasRequirement()),
                SUCCESS,
                isViewCall);
    }
}
