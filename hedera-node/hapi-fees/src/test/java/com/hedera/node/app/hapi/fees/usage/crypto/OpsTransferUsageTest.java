// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.crypto;

import static com.hedera.node.app.hapi.fees.test.AdapterUtils.feeDataFrom;
import static com.hedera.node.app.hapi.fees.test.IdUtils.asAccount;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.hapi.fees.test.IdUtils;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsTransferUsageTest {
    private final CryptoOpsUsage subject = new CryptoOpsUsage();

    @Test
    void matchesWithLegacyEstimate() {
        givenOp();
        // and given legacy estimate:
        final var expected = FeeData.newBuilder()
                .setNetworkdata(FeeComponents.newBuilder()
                        .setConstant(1)
                        .setBpt(18047)
                        .setVpt(3)
                        .setRbh(1))
                .setNodedata(FeeComponents.newBuilder()
                        .setConstant(1)
                        .setBpt(18047)
                        .setVpt(1)
                        .setBpr(4))
                .setServicedata(FeeComponents.newBuilder().setConstant(1).setRbh(904))
                .build();

        // when:
        final var accum = new UsageAccumulator();
        subject.cryptoTransferUsage(
                sigUsage,
                new CryptoTransferMeta(tokenMultiplier, 3, 7, 0, false),
                new BaseTransactionMeta(memo.getBytes().length, 3),
                accum,
                0,
                false);

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
                .addAccountAmounts(adjustFrom(a, -100))
                .addAccountAmounts(adjustFrom(b, 50))
                .addAccountAmounts(adjustFrom(c, 50))
                .build();
        op = CryptoTransferTransactionBody.newBuilder()
                .setTransfers(hbarAdjusts)
                .addTokenTransfers(TokenTransferList.newBuilder()
                        .setToken(anotherId)
                        .addAllTransfers(List.of(adjustFrom(a, -50), adjustFrom(b, 25), adjustFrom(c, 25))))
                .addTokenTransfers(TokenTransferList.newBuilder()
                        .setToken(anId)
                        .addAllTransfers(List.of(adjustFrom(b, -100), adjustFrom(c, 100))))
                .addTokenTransfers(TokenTransferList.newBuilder()
                        .setToken(yetAnotherId)
                        .addAllTransfers(List.of(adjustFrom(a, -15), adjustFrom(b, 15))))
                .build();

        setTxn();
    }

    private void setTxn() {
        txn = TransactionBody.newBuilder()
                .setMemo(memo)
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(Timestamp.newBuilder().setSeconds(now)))
                .setCryptoTransfer(op)
                .build();
    }

    private AccountAmount adjustFrom(final AccountID account, final long amount) {
        return AccountAmount.newBuilder()
                .setAmount(amount)
                .setAccountID(account)
                .build();
    }
}
