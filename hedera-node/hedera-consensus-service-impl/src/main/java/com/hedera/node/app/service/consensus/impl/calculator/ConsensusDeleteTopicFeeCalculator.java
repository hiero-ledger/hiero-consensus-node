package com.hedera.node.app.service.consensus.impl.calculator;


import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.CalculatorState;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

public class ConsensusDeleteTopicFeeCalculator implements ServiceFeeCalculator {

    @Override
    public void accumulateServiceFee(@NonNull TransactionBody txnBody, @Nullable CalculatorState calculatorState, @NonNull FeeResult feeResult, @NonNull FeeSchedule feeSchedule) {
        long keys = 0;
        final var op = txnBody.consensusDeleteTopicOrThrow();
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CONSENSUS_DELETE_TOPIC);
        feeResult.addServiceFee("Base fee for " + HederaFunctionality.CONSENSUS_DELETE_TOPIC, 1, serviceDef.baseFee());
        addExtraFee(feeResult, serviceDef, Extra.KEYS, feeSchedule, keys);
        addExtraFee(feeResult, serviceDef, Extra.SIGNATURES, feeSchedule, calculatorState.numTxnSignatures());
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.CONSENSUS_DELETE_TOPIC;
    }
}




