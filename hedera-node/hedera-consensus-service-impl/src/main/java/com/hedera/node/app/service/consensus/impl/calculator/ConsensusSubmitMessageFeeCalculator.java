// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class ConsensusSubmitMessageFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull TransactionBody txnBody,
            @Nullable CalculatorState calculatorState,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final var op = txnBody.consensusSubmitMessageOrThrow();
        final var topic =
                calculatorState.readableStore(ReadableTopicStore.class).getTopic(op.topicIDOrThrow());
        final var hasCustomFees = (topic != null && !topic.customFees().isEmpty());

        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE, hasCustomFees);

        feeResult.addServiceFee(1, serviceDef.baseFee());

        final var msgSize = op.message().length();
        addExtraFee(feeResult, serviceDef, Extra.BYTES, feeSchedule, msgSize);
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONSENSUS_SUBMIT_MESSAGE;
    }
}
