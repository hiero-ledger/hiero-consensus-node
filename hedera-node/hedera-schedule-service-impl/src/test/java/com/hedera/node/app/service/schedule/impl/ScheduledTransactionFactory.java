// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody.Builder;
import com.hedera.hapi.node.token.TokenBurnTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

public final class ScheduledTransactionFactory {
    private static final long FEE = 123L;
    // This memo includes several unicode values outside the BMP to ensure we adequately
    // test UTF-8 multi-byte sequences and do not make ASCII length assumptions.
    private static final String SCHEDULED_TRANSACTION_MEMO = "Les ħ2ᛏᚺᛂ🌕 goo";
    public static final SchedulableTransactionBody SAMPLE_BURN =
            createScheduledBurnWith(FEE, SCHEDULED_TRANSACTION_MEMO, 0L, 0L, 265L, 75231L);

    private ScheduledTransactionFactory() {}

    public static TransactionBody scheduleCreateTransactionWith(
            final Key adminKey,
            final String memo,
            final AccountID payer,
            final AccountID scheduler,
            final Timestamp validStart) {
        return scheduleCreateTransactionWith(SAMPLE_BURN, adminKey, memo, payer, scheduler, validStart, null, null);
    }

    public static TransactionBody scheduleCreateTransactionWith(
            final SchedulableTransactionBody body,
            final Key adminKey,
            final String memo,
            final AccountID payer,
            final AccountID scheduler,
            final Timestamp validStart,
            final Timestamp expirationTime,
            final Boolean waitForExpiry) {
        final Builder creation = ScheduleCreateTransactionBody.newBuilder();
        creation.memo(memo).scheduledTransactionBody(body);
        if (adminKey != null) {
            creation.adminKey(adminKey);
        }
        if (payer != null) {
            creation.payerAccountID(payer);
        }
        if (expirationTime != null) {
            creation.expirationTime(expirationTime);
        }
        if (waitForExpiry != null) {
            creation.waitForExpiry(waitForExpiry);
        }
        // temporary variables to prevent spotless issues
        final TransactionID.Builder idBuilder = TransactionID.newBuilder();
        idBuilder.transactionValidStart(validStart).accountID(scheduler);
        final TransactionBody.Builder result = TransactionBody.newBuilder();
        result.transactionID(idBuilder).scheduleCreate(creation);
        return result.build();
    }

    @NonNull
    private static SchedulableTransactionBody createScheduledBurnWith(
            final long fee,
            @NonNull final String memo,
            final long realm,
            final long shard,
            final long tokenNumber,
            final long amountToBurn) {
        final TokenID.Builder tokenToBurn = TokenID.newBuilder();
        tokenToBurn.realmNum(realm).shardNum(shard).tokenNum(tokenNumber);
        final TokenBurnTransactionBody.Builder body = TokenBurnTransactionBody.newBuilder();
        body.token(tokenToBurn).amount(amountToBurn);
        final SchedulableTransactionBody.Builder scheduled = SchedulableTransactionBody.newBuilder();
        scheduled.transactionFee(fee).memo(memo).tokenBurn(body);
        return scheduled.build();
    }
}
