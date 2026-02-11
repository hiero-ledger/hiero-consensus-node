package com.hedera.node.app.service.networkadmin.impl.calculator;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

/**
 * Fee calculator for {@link HederaFunctionality#TRANSACTION_GET_RECEIPT} queries.
 * <p>
 * Note: This is a free query (extends FreeQueryHandler), but we still provide
 * a calculator for completeness in the simple fees system.
 */
public class UncheckedSubmitFeeCalculator implements ServiceFeeCalculator {
    @Override
    public void accumulateServiceFee(
            @NonNull TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeecontext,
            @NonNull FeeResult feeResult,
            @NonNull FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.UNCHECKED_SUBMIT);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());
    }
    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.UNCHECKED_SUBMIT;
    }
}


