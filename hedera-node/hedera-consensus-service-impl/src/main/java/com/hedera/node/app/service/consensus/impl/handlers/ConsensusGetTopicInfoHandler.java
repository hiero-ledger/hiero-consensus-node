// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder.computeVariableSizedFieldsUsage;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TX_HASH_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getQueryFeeDataMatrices;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.getStateProofSize;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static com.hedera.node.app.spi.fees.Fees.CONSTANT_FEE_DATA;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoResponse;
import com.hedera.hapi.node.consensus.ConsensusTopicInfo;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.common.EntityCreate;
import com.hedera.node.app.hapi.fees.apis.common.FeesHelper;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONSENSUS_GET_TOPIC_INFO}.
 */
@Singleton
public class ConsensusGetTopicInfoHandler extends PaidQueryHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public ConsensusGetTopicInfoHandler() {
        // Dagger 2
    }

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.consensusGetTopicInfoOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ConsensusGetTopicInfoResponse.newBuilder().header(header);
        return Response.newBuilder().consensusGetTopicInfo(response).build();
    }

    @Override
    public boolean requiresNodePayment(@NonNull ResponseType responseType) {
        return responseType == ANSWER_ONLY || responseType == ANSWER_STATE_PROOF;
    }

    @Override
    public boolean needsAnswerOnlyCost(@NonNull ResponseType responseType) {
        return COST_ANSWER == responseType;
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        final var topicStore = context.createStore(ReadableTopicStore.class);
        final ConsensusGetTopicInfoQuery op = query.consensusGetTopicInfoOrThrow();
        if (op.hasTopicID()) {
            // The topic must exist
            final var topic = topicStore.getTopic(op.topicIDOrElse(TopicID.DEFAULT));
            mustExist(topic, INVALID_TOPIC_ID);
            if (topic.deleted()) {
                throw new PreCheckException(INVALID_TOPIC_ID);
            }
        } else {
            throw new PreCheckException(INVALID_TOPIC_ID);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var config = context.configuration().getConfigData(LedgerConfig.class);
        final var topicStore = context.createStore(ReadableTopicStore.class);
        final var op = query.consensusGetTopicInfoOrThrow();
        final var response = ConsensusGetTopicInfoResponse.newBuilder();
        final var topic = op.topicIDOrElse(TopicID.DEFAULT);
        response.topicID(topic);

        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        response.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var optionalInfo = infoForTopic(topic, topicStore, config);
            optionalInfo.ifPresent(response::topicInfo);
        }

        return Response.newBuilder().consensusGetTopicInfo(response).build();
    }

    /**
     * Provides information about a topic.
     *
     * @param topicID the topic to get information about
     * @param topicStore the topic store
     * @param config the LedgerConfig
     * @return the information about the topic
     */
    private Optional<ConsensusTopicInfo> infoForTopic(
            @NonNull final TopicID topicID,
            @NonNull final ReadableTopicStore topicStore,
            @NonNull final LedgerConfig config) {
        final var meta = topicStore.getTopic(topicID);
        if (meta == null) {
            return Optional.empty();
        } else {
            final var info = ConsensusTopicInfo.newBuilder();
            info.memo(meta.memo());
            info.runningHash(meta.runningHash());
            info.sequenceNumber(meta.sequenceNumber());
            info.expirationTime(
                    Timestamp.newBuilder().seconds(meta.expirationSecond()).build());
            if (!isEmpty(meta.adminKey())) info.adminKey(meta.adminKey());
            if (!isEmpty(meta.submitKey())) info.submitKey(meta.submitKey());
            info.autoRenewPeriod(Duration.newBuilder().seconds(meta.autoRenewPeriod()));
            if (meta.hasAutoRenewAccountId()) info.autoRenewAccount(meta.autoRenewAccountId());
            if (meta.hasFeeScheduleKey()) info.feeScheduleKey(meta.feeScheduleKey());
            if (!meta.feeExemptKeyList().isEmpty()) info.feeExemptKeyList(meta.feeExemptKeyList());
            if (!meta.customFees().isEmpty()) info.customFees(meta.customFees());

            info.ledgerId(config.id());
            return Optional.of(info.build());
        }
    }

    @NonNull
    @Override
    public Fees computeFees(@NonNull QueryContext queryContext) {
        final var query = queryContext.query();
        final var topicStore = queryContext.createStore(ReadableTopicStore.class);
        final var op = query.consensusGetTopicInfoOrThrow();
        final var topicId = op.topicIDOrElse(TopicID.DEFAULT);
        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        final var topic = topicStore.getTopic(topicId);

        final var oldFees = queryContext.feeCalculator().legacyCalculate(ignored -> usageGivenTypeAndTopic(topic, responseType));
        if(queryContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            EntityCreate entity = FeesHelper.makeEntity(HederaFunctionality.CONSENSUS_GET_TOPIC_INFO, "Query topic info", 0, false);
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", 0);
            params.put("numKeys", 0);
            params.put("hasCustomFee", YesOrNo.NO);
            FeeResult simpleFee = entity.computeFee(params);
            return new Fees(oldFees.nodeFee(), 0, oldFees.serviceFee(), simpleFee.fee);
        } else {
            return oldFees;
        }
    }

    private FeeData usageGivenTypeAndTopic(@Nullable final Topic topic, @NonNull final ResponseType responseType) {
        requireNonNull(responseType);
        if (topic == null) {
            return CONSTANT_FEE_DATA;
        }
        final var bpr = BASIC_QUERY_RES_HEADER
                + getStateProofSize(CommonPbjConverters.fromPbjResponseType(responseType))
                + BASIC_ENTITY_ID_SIZE
                + getTopicInfoSize(topic);
        final var feeMatrices = FeeComponents.newBuilder()
                .setBpt(BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE)
                .setVpt(0)
                .setRbh(0)
                .setSbh(0)
                .setGas(0)
                .setTv(0)
                .setBpr(bpr)
                .setSbpr(0)
                .build();
        return getQueryFeeDataMatrices(feeMatrices);
    }

    private static int getTopicInfoSize(@NonNull final Topic topic) {
        /* Three longs in a topic representation: sequenceNumber, expirationTime, autoRenewPeriod */
        return TX_HASH_SIZE
                + 3 * LONG_SIZE
                + computeVariableSizedFieldsUsage(
                        CommonPbjConverters.fromPbj(topic.adminKeyOrElse(Key.DEFAULT)),
                        CommonPbjConverters.fromPbj(topic.submitKeyOrElse(Key.DEFAULT)),
                        topic.memo(),
                        topic.hasAutoRenewAccountId());
    }
}
