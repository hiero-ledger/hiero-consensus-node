// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.calculator;

import static org.hiero.hapi.fees.FeeKeyUtils.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ConsensusUpdateTopicFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull TransactionBody txnBody,
            @Nullable SimpleFeeContext feeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final var op = txnBody.consensusUpdateTopicOrThrow();
        long keys = 0;
        if (op.hasAdminKey()) {
            keys += countKeys(op.adminKeyOrThrow());
        }
        if (op.hasFeeScheduleKey()) {
            keys += countKeys(op.feeScheduleKeyOrThrow());
        }
        if (op.hasSubmitKey()) {
            keys += countKeys(op.submitKeyOrThrow());
        }
        if (op.hasFeeExemptKeyList()) {
            for (var key : op.feeExemptKeyList().keys()) {
                keys += countKeys(key);
            }
        }
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_UPDATE_TOPIC);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.KEYS, feeSchedule, keys);
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONSENSUS_UPDATE_TOPIC;
    }
}
