// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.hasschedulecapacity;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.schedule.ScheduleServiceApi;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * An HSS call that uses the {@link ScheduleServiceApi#hasContractCallCapacity(long, java.time.Instant, long, AccountID)} to
 * check if there is capacity to schedule a contract call at a given second with a given gas limit.
 */
public class HasScheduleCapacityCall extends AbstractCall {
    private final long gasLimit;
    private final long consensusSecond;
    private final AccountID senderId;

    public HasScheduleCapacityCall(
            @NonNull final HssCallAttempt attempt, final long consensusSecond, final long gasLimit) {
        this(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                consensusSecond,
                gasLimit,
                attempt.senderId());
    }

    public HasScheduleCapacityCall(
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final SystemContractGasCalculator gasCalculator,
            final long consensusSecond,
            final long gasLimit,
            @NonNull final AccountID senderId) {
        // A bit counterintuitive, but set isViewCall=false here so we externalize nothing
        // (neither a dispatched transaction, nor a synthetic ContractFunctionResult)
        super(gasCalculator, enhancement, false);
        this.gasLimit = gasLimit;
        this.consensusSecond = consensusSecond;
        this.senderId = requireNonNull(senderId);
    }

    @Override
    public @NonNull PricedResult execute(@NonNull final MessageFrame frame) {
        final boolean hasCapacity = nativeOperations().canScheduleContractCall(consensusSecond, gasLimit, senderId);
        return gasOnly(
                successResult(
                        HasScheduleCapacityTranslator.HAS_SCHEDULE_CAPACITY
                                .getOutputs()
                                .encode(Tuple.singleton(hasCapacity)),
                        gasCalculator.viewGasRequirement()),
                SUCCESS,
                isViewCall);
    }
}
