// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.calculator;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.SimpleFeeContext;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;

public class TokenAirdropFeeCalculator extends CryptoTransferFeeCalculator {

    @Override
    public void accumulateServiceFee(
            @NonNull final TransactionBody txnBody,
            @NonNull final SimpleFeeContext simpleFeeContext,
            @NonNull final FeeResult feeResult,
            @NonNull final FeeSchedule feeSchedule) {
        if (simpleFeeContext.feeContext() != null) {
            final var tokenConfig =
                    simpleFeeContext.feeContext().configuration().getConfigData(TokensConfig.class);
            validateTrue(tokenConfig.airdropsEnabled(), NOT_SUPPORTED);
        }
        final var op = txnBody.tokenAirdropOrThrow();
        final var cryptoTransferBody = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(op.tokenTransfers())
                .build();

        final var syntheticCryptoTransfer = TransactionBody.newBuilder()
                .cryptoTransfer(cryptoTransferBody)
                .transactionID(txnBody.transactionID())
                .build();

        super.accumulateServiceFee(syntheticCryptoTransfer, simpleFeeContext, feeResult, feeSchedule);
    }

    public TransactionBody.DataOneOfType getTransactionType() {
        return TransactionBody.DataOneOfType.TOKEN_AIRDROP;
    }
}
