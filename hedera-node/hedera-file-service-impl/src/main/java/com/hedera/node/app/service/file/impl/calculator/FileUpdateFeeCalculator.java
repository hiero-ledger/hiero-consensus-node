// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.STATE_BYTES;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeKeyUtils;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class FileUpdateFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var op = txnBody.fileUpdateOrThrow();
        long keyCount = 0L;
        if (op.hasKeys()) {
            keyCount = txnBody.fileUpdateOrThrow().keys().keys().stream()
                    .mapToLong(FeeKeyUtils::countKeys)
                    .sum();
        }
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.FILE_UPDATE);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, KEYS, feeSchedule, keyCount);
        addExtraFee(
                feeResult, serviceDef, STATE_BYTES, feeSchedule, op.contents().length());
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.FILE_UPDATE;
    }
}
