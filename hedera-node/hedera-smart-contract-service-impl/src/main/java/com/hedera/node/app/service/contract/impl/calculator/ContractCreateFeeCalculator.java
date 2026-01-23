// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.calculator;

import static com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.HOOK_UPDATES;
import static org.hiero.hapi.support.fees.Extra.KEYS;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ContractCreateFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var op = txnBody.contractCreateInstanceOrThrow();
        final var adminKey = op.adminKey();
        final long keys = adminKey != null ? countKeys(adminKey) : 0;
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CONTRACT_CREATE);
        feeResult.addServiceFee(1, serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, KEYS, feeSchedule, keys);
        if (!op.hookCreationDetails().isEmpty()) {
            addExtraFee(
                    feeResult,
                    serviceDef,
                    HOOK_UPDATES,
                    feeSchedule,
                    op.hookCreationDetails().size());
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONTRACT_CREATE_INSTANCE;
    }
}
