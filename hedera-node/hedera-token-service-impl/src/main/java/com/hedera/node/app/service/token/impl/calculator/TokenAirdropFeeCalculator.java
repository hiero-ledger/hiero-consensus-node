// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

public class TokenAirdropFeeCalculator extends CryptoTransferFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext context,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        final var op = txnBody.tokenAirdropOrThrow();
        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(op.tokenTransfers())
                .build();

        final var syntheticCryptoTransfer = TransactionBody.newBuilder()
                .cryptoTransfer(cryptoTransferBody)
                .transactionID(txnBody.transactionID())
                .build();

        super.accumulateServiceFee(syntheticCryptoTransfer, context, feeResult, feeSchedule);
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_AIRDROP;
    }
}
