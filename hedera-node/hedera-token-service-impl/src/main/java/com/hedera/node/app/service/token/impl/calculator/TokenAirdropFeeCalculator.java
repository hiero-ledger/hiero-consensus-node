// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

public class TokenAirdropFeeCalculator extends CryptoTransferFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @Nullable final FeeContext feeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule,
            EstimationMode mode) {
        final var op = txnBody.tokenAirdropOrThrow();
        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(op.tokenTransfers())
                .build();

        final var syntheticCryptoTransfer = TransactionBody.newBuilder()
                .cryptoTransfer(cryptoTransferBody)
                .transactionID(txnBody.transactionID())
                .build();

        super.accumulateServiceFee(syntheticCryptoTransfer, feeContext, feeResult, feeSchedule, mode);
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_AIRDROP;
    }
}
