// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.usage.schedule;

import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.node.app.hapi.fees.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hedera.node.app.hapi.fees.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.node.app.hapi.fees.usage.schedule.entities.ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_RICH_INSTANT_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_TX_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BOOL_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getAccountKeyStorageSize;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.usage.EstimatorFactory;
import com.hedera.node.app.hapi.fees.usage.QueryUsage;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.TxnUsageEstimator;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ScheduleOpsUsage {
    /* Scheduled transaction ids have the scheduled=true flag set */
    private static final long SCHEDULED_TXN_ID_SIZE = (1L * BASIC_TX_ID_SIZE) + BOOL_SIZE;

    @VisibleForTesting
    EstimatorFactory txnEstimateFactory = TxnUsageEstimator::new;

    @VisibleForTesting
    Function<ResponseType, QueryUsage> queryEstimateFactory = QueryUsage::new;

    @Inject
    public ScheduleOpsUsage() {
        // Default constructor
    }

    public FeeData scheduleInfoUsage(Query scheduleInfo, ExtantScheduleContext ctx) {
        var op = scheduleInfo.scheduleGetInfo();

        var estimate = queryEstimateFactory.apply(op.header().responseType());
        estimate.addTb(BASIC_ENTITY_ID_SIZE);
        estimate.addRb(ctx.nonBaseRb());

        return estimate.get();
    }

    public FeeData scheduleCreateUsage(TransactionBody scheduleCreate, SigUsage sigUsage, long lifetimeSecs) {
        var op = scheduleCreate.scheduleCreate();

        var scheduledTxn = op.scheduledTransactionBody();
        long msgBytesUsed =
                SchedulableTransactionBody.PROTOBUF.toBytes(scheduledTxn).length()
                        + op.memo().getBytes().length;
        if (op.hasPayerAccountID()) {
            msgBytesUsed += BASIC_ENTITY_ID_SIZE;
        }

        var creationCtx = ExtantScheduleContext.newBuilder()
                .setScheduledTxn(scheduledTxn)
                .setNumSigners(SCHEDULE_ENTITY_SIZES.estimatedScheduleSigs(sigUsage))
                .setMemo(op.memo())
                .setResolved(false);
        if (op.hasAdminKey()) {
            var adminKey = op.adminKey();
            msgBytesUsed += getAccountKeyStorageSize(adminKey);
            creationCtx.setAdminKey(adminKey);
        } else {
            creationCtx.setNoAdminKey();
        }

        var estimate = txnEstimateFactory.get(sigUsage, scheduleCreate, ESTIMATOR_UTILS);
        estimate.addBpt(msgBytesUsed);
        estimate.addRbs(creationCtx.build().nonBaseRb() * lifetimeSecs);

        /* The receipt of a schedule create includes both the id of the created schedule
        and the transaction id to use for querying the record of the scheduled txn. */
        estimate.addNetworkRbs(
                (BASIC_ENTITY_ID_SIZE + SCHEDULED_TXN_ID_SIZE) * USAGE_PROPERTIES.legacyReceiptStorageSecs());

        if (scheduledTxn.hasContractCall()) {
            return estimate.get(SCHEDULE_CREATE_CONTRACT_CALL);
        }

        return estimate.get();
    }

    public FeeData scheduleSignUsage(TransactionBody scheduleSign, SigUsage sigUsage, long scheduleExpiry) {
        var estimate = txnEstimateFactory.get(sigUsage, scheduleSign, ESTIMATOR_UTILS);

        estimate.addBpt(BASIC_ENTITY_ID_SIZE);

        int estNewSigners = SCHEDULE_ENTITY_SIZES.estimatedScheduleSigs(sigUsage);
        long lifetime = ESTIMATOR_UTILS.relativeLifetime(scheduleSign, scheduleExpiry);
        estimate.addRbs(SCHEDULE_ENTITY_SIZES.bytesUsedForSigningKeys(estNewSigners) * lifetime);

        estimate.addNetworkRbs(SCHEDULED_TXN_ID_SIZE * USAGE_PROPERTIES.legacyReceiptStorageSecs());

        return estimate.get();
    }

    public FeeData scheduleDeleteUsage(TransactionBody scheduleDelete, SigUsage sigUsage, long scheduleExpiry) {
        var estimate = txnEstimateFactory.get(sigUsage, scheduleDelete, ESTIMATOR_UTILS);

        estimate.addBpt(BASIC_ENTITY_ID_SIZE);

        long lifetime = ESTIMATOR_UTILS.relativeLifetime(scheduleDelete, scheduleExpiry);
        estimate.addRbs(BASIC_RICH_INSTANT_SIZE * lifetime);

        return estimate.get();
    }
}
