// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.calculator;

import static org.hiero.hapi.fees.FeeKeyUtils.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.SCHEDULE_CREATE_CONTRACT_CALL_BASE;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ScheduleCreateFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var op = txnBody.scheduleCreateOrThrow();
        final var adminKey = op.adminKey();
        final long keys = adminKey != null ? countKeys(adminKey) : 0;
        final var schedulesContractCall = op.scheduledTransactionBodyOrThrow().hasContractCall();
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.SCHEDULE_CREATE);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, KEYS, feeSchedule, keys);
        if (schedulesContractCall) {
            // Add extra fee for scheduling a contract call
            addExtraFee(feeResult, serviceDef, SCHEDULE_CREATE_CONTRACT_CALL_BASE, feeSchedule, 1);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.SCHEDULE_CREATE;
    }
}
