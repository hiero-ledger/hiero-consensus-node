// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
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
            @Nullable SimpleFeeContext simpleFeeContext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());

        final var op = txnBody.consensusSubmitMessageOrThrow();

        final var msgSize = op.message().length();
        addExtraFee(feeResult, serviceDef, Extra.BYTES, feeSchedule, msgSize);
        if (simpleFeeContext.feeContext() != null) {
            final var topic = simpleFeeContext
                    .feeContext()
                    .readableStore(ReadableTopicStore.class)
                    .getTopic(op.topicIDOrThrow());
            final var hasCustomFees = (topic != null && !topic.customFees().isEmpty());
            if (hasCustomFees) {
                addExtraFee(feeResult, serviceDef, Extra.CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE, feeSchedule, 1);
            }
        }
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONSENSUS_SUBMIT_MESSAGE;
    }
}
