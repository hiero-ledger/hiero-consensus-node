// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoQuery;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoResponse;
import com.hedera.hapi.node.scheduled.ScheduleGetInfoResponse.Builder;
import com.hedera.hapi.node.scheduled.ScheduleInfo;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.usage.schedule.ExtantScheduleContext;
import com.hedera.node.app.hapi.fees.usage.schedule.ScheduleOpsUsage;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class provides an implementation of the {@link HederaFunctionality#SCHEDULE_GET_INFO} query.
 */
@Singleton
public class ScheduleGetInfoHandler extends PaidQueryHandler {
    private final ScheduleOpsUsage legacyUsage;

    /**
     * Constructor is used by the Dagger dependency injection framework to provide the necessary dependencies
     * to the handler.
     * The handler is responsible for handling the {@link HederaFunctionality#SCHEDULE_GET_INFO} query.
     *
     * @param legacyUsage the legacy usage
     */
    @Inject
    public ScheduleGetInfoHandler(ScheduleOpsUsage legacyUsage) {
        this.legacyUsage = legacyUsage;
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        Objects.requireNonNull(query);
        final ScheduleGetInfoQuery expectedQuery = query.scheduleGetInfo();
        return expectedQuery != null ? expectedQuery.header() : null;
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        Objects.requireNonNull(header);
        final var response = ScheduleGetInfoResponse.newBuilder().header(header);
        return Response.newBuilder().scheduleGetInfo(response).build();
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull final QueryContext context) {
        // Need to work out if this is correct, note we effectively (much) more than double total effort
        // here just to calculate fees based on a single instance of that effort...
        final Schedule found = findSchedule(context);
        if (found != null) {
            final LedgerConfig ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
            final ScheduleInfo.Builder builder = ScheduleInfo.newBuilder();
            buildFromSchedule(builder, found, ledgerConfig);
            return context.feeCalculator()
                    .legacyCalculate(sigValueObj -> usageGiven(fromPbj(context.query()), fromPbj(builder.build())));
        } else {
            return context.feeCalculator().calculate();
        }
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        Objects.requireNonNull(context);
        final ScheduleGetInfoQuery request = context.query().scheduleGetInfo();
        if (request != null && request.hasHeader()) {
            if (findSchedule(context) == null) {
                throw new PreCheckException(ResponseCodeEnum.INVALID_SCHEDULE_ID);
            }
        } else {
            throw new PreCheckException(ResponseCodeEnum.INVALID_TRANSACTION);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(header);
        final LedgerConfig ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final Builder infoBuilder = ScheduleGetInfoResponse.newBuilder();
        infoBuilder.header(header);
        if (shouldHandle(context, header)) {
            final Schedule scheduleFound = findSchedule(context);
            if (scheduleFound != null) {
                final ScheduleInfo.Builder builder = ScheduleInfo.newBuilder();
                buildFromSchedule(builder, scheduleFound, ledgerConfig);
                infoBuilder.scheduleInfo(builder);
            } else {
                infoBuilder.header(
                        header.copyBuilder().nodeTransactionPrecheckCode(ResponseCodeEnum.INVALID_SCHEDULE_ID));
            }
        }
        return Response.newBuilder().scheduleGetInfo(infoBuilder).build();
    }

    private boolean shouldHandle(final QueryContext context, final ResponseHeader header) {
        final ScheduleGetInfoQuery query = context.query().scheduleGetInfo();
        return query != null
                && query.header() != null
                && query.hasScheduleID()
                && query.header().responseType() != ResponseType.COST_ANSWER
                && header.nodeTransactionPrecheckCode() == ResponseCodeEnum.OK;
    }

    private void buildFromSchedule(
            final ScheduleInfo.Builder builder, final Schedule scheduleFound, final LedgerConfig config) {
        builder.adminKey(scheduleFound.adminKey());
        builder.creatorAccountID(scheduleFound.schedulerAccountId());
        builder.waitForExpiry(scheduleFound.waitForExpiry());
        builder.scheduleID(scheduleFound.scheduleId());
        builder.memo(scheduleFound.memo());
        builder.payerAccountID(scheduleFound.payerAccountId());
        builder.ledgerId(config.id());
        if (scheduleFound.executed()) {
            builder.executionTime(scheduleFound.resolutionTime());
        }
        if (scheduleFound.deleted()) {
            builder.deletionTime(scheduleFound.resolutionTime());
        }
        builder.scheduledTransactionID(HandlerUtility.transactionIdForScheduled(scheduleFound));
        builder.expirationTime(timestampFromSeconds(scheduleFound.calculatedExpirationSecond()));
        builder.signers(makeKeyList(scheduleFound.signatories()));
        builder.scheduledTransactionBody(scheduleFound.scheduledTransaction());
    }

    private KeyList makeKeyList(final List<Key> signatories) {
        return KeyList.newBuilder().keys(signatories).build();
    }

    private Schedule findSchedule(final QueryContext context) {
        final Query contextQuery = context.query();
        final ReadableScheduleStore queryStore = context.createStore(ReadableScheduleStore.class);
        final ScheduleGetInfoQuery scheduleQuery = contextQuery.scheduleGetInfoOrThrow();
        final ScheduleID idToQuery = scheduleQuery.scheduleID();
        return idToQuery != null ? queryStore.get(idToQuery) : null;
    }

    private Timestamp.Builder timestampFromSeconds(long secondsSinceEpoch) {
        return Timestamp.newBuilder().seconds(secondsSinceEpoch).nanos(0);
    }

    public FeeData usageGiven(
            final com.hederahashgraph.api.proto.java.Query query,
            final com.hederahashgraph.api.proto.java.ScheduleInfo info) {
        if (info != null) {
            final var scheduleCtxBuilder = ExtantScheduleContext.newBuilder()
                    .setScheduledTxn(info.getScheduledTransactionBody())
                    .setMemo(info.getMemo())
                    .setNumSigners(info.getSigners().getKeysCount())
                    .setResolved(info.hasExecutionTime() || info.hasDeletionTime());
            if (info.hasAdminKey()) {
                scheduleCtxBuilder.setAdminKey(info.getAdminKey());
            } else {
                scheduleCtxBuilder.setNoAdminKey();
            }
            return legacyUsage.scheduleInfoUsage(query, scheduleCtxBuilder.build());
        } else {
            return CONSTANT_FEE_DATA;
        }
    }
}
