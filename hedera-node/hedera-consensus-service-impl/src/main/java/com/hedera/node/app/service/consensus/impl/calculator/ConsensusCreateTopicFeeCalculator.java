// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.calculator;

import static org.hiero.hapi.fees.FeeKeyUtils.countKeys;
import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ConsensusCreateTopicFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeecontext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        long keys = 0;
        final var op = txnBody.consensusCreateTopicOrThrow();
        if (op.hasAdminKey()) {
            keys += countKeys(op.adminKey());
        }
        if (op.hasFeeScheduleKey()) {
            keys += countKeys(op.feeScheduleKey());
        }
        if (op.hasSubmitKey()) {
            keys += countKeys(op.submitKey());
        }
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_CREATE_TOPIC);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.KEYS, feeSchedule, keys);
        final var hasCustomFees = !op.customFees().isEmpty();
        if (hasCustomFees) {
            addExtraFee(feeResult, serviceDef, Extra.CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE, feeSchedule, 1);
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONSENSUS_CREATE_TOPIC;
    }
}
