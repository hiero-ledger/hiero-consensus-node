// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONSENSUS_DELETE_TOPIC}.
 */
@Singleton
public class ConsensusDeleteTopicHandler implements TransactionHandler {
    /**
     * Default constructor for injection.
     */
    @Inject
    public ConsensusDeleteTopicHandler() {
        // Exists for injection
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final TransactionBody txn = context.body();
        final ConsensusDeleteTopicTransactionBody op = txn.consensusDeleteTopicOrThrow();
        validateTruePreCheck(op.hasTopicID(), INVALID_TOPIC_ID);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var op = context.body().consensusDeleteTopicOrThrow();
        final var topicStore = context.createStore(ReadableTopicStore.class);
        // The topic ID must be present on the transaction and the topic must exist.
        mustExist(op.topicID(), INVALID_TOPIC_ID);
        final var topic = topicStore.getTopic(op.topicIDOrThrow());
        mustExist(topic, INVALID_TOPIC_ID);
        // To delete a topic, the transaction must be signed by the admin key. If there is no admin
        // key, then it is impossible to delete the topic.
        context.requireKeyOrThrow(topic.adminKey(), UNAUTHORIZED);
    }

    /**
     * Given the appropriate context, deletes a topic.
     *
     * @param context the {@link HandleContext} of the active transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context, "The argument 'context' must not be null");

        final var op = context.body().consensusDeleteTopicOrThrow();
        final var topicStore = context.storeFactory().writableStore(WritableTopicStore.class);
        final var topicId = op.topicIDOrElse(TopicID.DEFAULT);
        final Topic topic = topicStore.getTopic(topicId);
        // preHandle already checks for topic existence, so topic should never be null.

        /* Topics without adminKeys can't be deleted.*/
        if (topic.adminKey() == null) {
            throw new HandleException(UNAUTHORIZED);
        }

        /* Copy all the fields from existing topic and change deleted flag */
        final var topicBuilder = new Topic.Builder()
                .topicId(topic.topicId())
                .adminKey(topic.adminKey())
                .submitKey(topic.submitKey())
                .autoRenewAccountId(topic.autoRenewAccountId())
                .autoRenewPeriod(topic.autoRenewPeriod())
                .expirationSecond(topic.expirationSecond())
                .memo(topic.memo())
                .runningHash(topic.runningHash())
                .sequenceNumber(topic.sequenceNumber());
        topicBuilder.deleted(true);

        /* --- Put the modified topic. It will be in underlying state's modifications map.
        It will not be committed to state until commit is called on the state.--- */
        topicStore.put(topicBuilder.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();

        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(CommonPbjConverters.fromPbj(op), sigValueObj));
    }

    @Override
    public @NonNull FeeResult calculateFeeResult(@NonNull FeeContext feeContext) {
        final var entity = FeeModelRegistry.lookupModel(HederaFunctionality.CONSENSUS_DELETE_TOPIC);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, (long) feeContext.numTxnSignatures());
        return entity.computeFee(
                params,
                feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT).getSimpleFeesSchedule());
    }

    private FeeData usageGiven(
            final com.hederahashgraph.api.proto.java.TransactionBody txn, final SigValueObj sigUsage) {
        return ConsensusServiceFeeBuilder.getConsensusDeleteTopicFee(txn, sigUsage);
    }
}
