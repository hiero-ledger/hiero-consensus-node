// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.test.AdapterUtils.feeDataFrom;
import static com.hedera.node.app.hapi.fees.test.IdUtils.asAccount;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsTransferUsageTest {
    private final CryptoOpsUsage subject = new CryptoOpsUsage();

    @Test
    void matchesWithLegacyEstimate() {
        givenOp();
        // and given legacy estimate:
        final var expected = FeeData.newBuilder()
                .networkdata(
                        FeeComponents.newBuilder().constant(1).bpt(18047).vpt(3).rbh(1))
                .nodedata(
                        FeeComponents.newBuilder().constant(1).bpt(18047).vpt(1).bpr(4))
                .servicedata(FeeComponents.newBuilder().constant(1).rbh(904))
                .build();

        // when:
        final var accum = new UsageAccumulator();
        subject.cryptoTransferUsage(
                sigUsage,
                new CryptoTransferMeta(tokenMultiplier, 3, 7, 0),
                new BaseTransactionMeta(memo.getBytes().length, 3),
                accum);

        // then:
        assertEquals(expected, feeDataFrom(accum));
    }

    private final int tokenMultiplier = 60;
    private final int numSigs = 3, sigSize = 100, numPayerKeys = 1;
    private final String memo = "Yikes who knows";
    private final SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
    private final long now = 1_234_567L;
    private final AccountID a = asAccount("1.2.3");
    private final AccountID b = asAccount("2.3.4");
    private final AccountID c = asAccount("3.4.5");
    private final TokenID anId = IdUtils.asToken("0.0.75231");
    private final TokenID anotherId = IdUtils.asToken("0.0.75232");
    private final TokenID yetAnotherId = IdUtils.asToken("0.0.75233");

    private TransactionBody txn;
    private CryptoTransferTransactionBody op;

    private void givenOp() {
        final var hbarAdjusts = TransferList.newBuilder()
                .accountAmounts(adjustFrom(a, -100), adjustFrom(b, 50), adjustFrom(c, 50))
                .build();
        op = CryptoTransferTransactionBody.newBuilder()
                .transfers(hbarAdjusts)
                .tokenTransfers(
                        TokenTransferList.newBuilder()
                                .token(anotherId)
                                .transfers(List.of(adjustFrom(a, -50), adjustFrom(b, 25), adjustFrom(c, 25)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(anId)
                                .transfers(List.of(adjustFrom(b, -100), adjustFrom(c, 100)))
                                .build(),
                        TokenTransferList.newBuilder()
                                .token(yetAnotherId)
                                .transfers(List.of(adjustFrom(a, -15), adjustFrom(b, 15)))
                                .build())
                .build();

        setTxn();
    }

    private void setTxn() {
        txn = TransactionBody.newBuilder()
                .memo(memo)
                .transactionID(TransactionID.newBuilder()
                        .transactionValidStart(Timestamp.newBuilder().seconds(now)))
                .cryptoTransfer(op)
                .build();
    }

    private AccountAmount adjustFrom(final AccountID account, final long amount) {
        return AccountAmount.newBuilder().amount(amount).accountID(account).build();
    }
}
