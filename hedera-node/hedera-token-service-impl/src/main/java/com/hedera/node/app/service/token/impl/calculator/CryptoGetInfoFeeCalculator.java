// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

/**
 * Calculates CryptoGetInfo query fees per HIP-1261.
 * Base fee: 1,000,000 tinycents ($0.0001 USD), no extras.
 */
@Singleton
public class CryptoGetInfoFeeCalculator implements ServiceFeeCalculator {

    @Inject
    public CryptoGetInfoFeeCalculator() {
        // Dagger injection
    }

    @Override
    public void accumulateQueryFee(
            @NonNull final Query query,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final ServiceFeeDefinition serviceDef = lookupServiceFee(feeSchedule, HederaFunctionality.CRYPTO_GET_INFO);
        feeResult.addServiceFee(1, serviceDef.baseFee());
    }

    @Override
    public Query.QueryOneOfType getQueryType() {
        return Query.QueryOneOfType.CRYPTO_GET_INFO;
    }

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        throw new UnsupportedOperationException("CryptoGetInfoFeeCalculator is a query-only calculator");
    }

    @Override
    public TransactionBody.DataOneOfType getTransactionType() {
        return null;
    }
}
