// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ConsensusUpdateTopicFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull TransactionBody txnBody, @NonNull FeeResult feeResult, @NonNull FeeSchedule feeSchedule) {
        final var op = txnBody.consensusUpdateTopicOrThrow();
        long keys = 0;
        if (op.hasAdminKey()) {
            keys += 1;
        }
        if (op.hasFeeScheduleKey()) {
            keys += 1;
        }
        if (op.hasSubmitKey()) {
            keys += 1;
        }
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_UPDATE_TOPIC);
        feeResult.addServiceBase(serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.KEYS, feeSchedule, keys);
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONSENSUS_UPDATE_TOPIC;
    }
}
