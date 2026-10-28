// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static org.hiero.hapi.fees.FeeScheduleUtils.lookupServiceFee;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;

public class TokenDissociateFeeCalculator implements ServiceFeeCalculator {
    private static final Logger log = LogManager.getLogger(TokenDissociateFeeCalculator.class);
    private static final AtomicBoolean MISSING_EXTRA_WARNED = new AtomicBoolean();

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var op = txnBody.tokenDissociateOrThrow();
        final ServiceFeeDefinition serviceDef =
                lookupServiceFee(feeSchedule, HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT);
        feeResult.setServiceBaseFeeTinycents(serviceDef.baseFee());

        // Charged per token; a schedule without the extra charges only the base fee
        if (serviceDef.extras().stream().anyMatch(ref -> ref.name() == Extra.TOKEN_DISSOCIATE)) {
            addExtraFee(
                    feeResult,
                    serviceDef,
                    Extra.TOKEN_DISSOCIATE,
                    feeSchedule,
                    op.tokens().size());
        } else if (MISSING_EXTRA_WARNED.compareAndSet(false, true)) {
            log.warn("Fee schedule has no TOKEN_DISSOCIATE extra; charging TokenDissociate base fee only");
        }
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_DISSOCIATE;
    }
}
