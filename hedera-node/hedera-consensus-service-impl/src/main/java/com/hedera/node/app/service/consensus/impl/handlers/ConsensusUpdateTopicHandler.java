// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_ACCOUNT_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FEE_SCHEDULE_KEY_NOT_SET;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_SCHEDULE_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_KEY_IN_FEE_EXEMPT_KEY_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder.getConsensusUpdateTopicFee;
import static com.hedera.node.app.hapi.utils.fee.ConsensusServiceFeeBuilder.getUpdateTopicRbsIncrease;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.isEmpty;
import static com.hedera.node.app.spi.validation.AttributeValidator.isImmutableKey;
import static com.hedera.node.app.spi.validation.AttributeValidator.isKeyRemoval;
import static com.hedera.node.app.spi.validation.ExpiryMeta.NA;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.common.EntityCreate;
import com.hedera.node.app.hapi.fees.apis.common.FeesHelper;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.consensus.impl.validators.ConsensusCustomFeesValidator;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.TopicsConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONSENSUS_UPDATE_TOPIC}.
 */
@Singleton
public class ConsensusUpdateTopicHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(ConsensusUpdateTopicHandler.class);

    private final ConsensusCustomFeesValidator customFeesValidator;

    /**
     * Default constructor for injection.
     * @param customFeesValidator custom fees validator
     */
    @Inject
    public ConsensusUpdateTopicHandler(@NonNull final ConsensusCustomFeesValidator customFeesValidator) {
        requireNonNull(customFeesValidator);
        this.customFeesValidator = customFeesValidator;
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        final ConsensusUpdateTopicTransactionBody op = txn.consensusUpdateTopicOrThrow();
        validateTruePreCheck(op.hasTopicID(), INVALID_TOPIC_ID);

        if (op.hasFeeExemptKeyList()) {
            final var uniqueKeysCount =
                    op.feeExemptKeyList().keys().stream().distinct().count();
            validateTruePreCheck(
                    uniqueKeysCount == op.feeExemptKeyList().keys().size(),
                    FEE_EXEMPT_KEY_LIST_CONTAINS_DUPLICATED_KEYS);
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().consensusUpdateTopicOrThrow();
        final var topicStore = context.createStore(ReadableTopicStore.class);

        // The topic ID must be present on the transaction and the topic must exist.
        final var topic = topicStore.getTopic(op.topicIDOrElse(TopicID.DEFAULT));
        mustExist(topic, INVALID_TOPIC_ID);
        validateFalsePreCheck(topic.deleted(), INVALID_TOPIC_ID);

        // Extending the expiry is the *only* update operation permitted without an admin key. So if that is the
        // only thing this transaction is doing, then we don't need to worry about checking any additional keys.
        if (onlyExtendsExpiry(op)) {
            return;
        }

        // Any other modifications on this topic require the admin key.
        context.requireKeyOrThrow(topic.adminKey(), UNAUTHORIZED);

        // If the transaction is setting a new admin key, then the transaction must also be signed by that new key
        if (op.hasAdminKey()) {
            context.requireKey(op.adminKeyOrThrow());
        }

        // If the transaction is setting a new account for auto-renewals, then that account must also
        // have signed the transaction
        if (op.hasAutoRenewAccount()) {
            final var autoRenewAccountID = op.autoRenewAccountOrThrow();
            if (!designatesAccountRemoval(autoRenewAccountID)) {
                context.requireKeyOrThrow(autoRenewAccountID, INVALID_AUTORENEW_ACCOUNT);
            }
        }

        // If we change the custom fees the topic needs to have a fee schedule key, and it needs to sign the transaction
        if (op.hasCustomFees()) {
            validateTruePreCheck(topic.hasFeeScheduleKey(), FEE_SCHEDULE_KEY_NOT_SET);
            context.requireKey(topic.feeScheduleKey());
        }
    }

    private boolean onlyExtendsExpiry(@NonNull final ConsensusUpdateTopicTransactionBody op) {
        return op.hasExpirationTime()
                && !op.hasMemo()
                && !op.hasAdminKey()
                && !op.hasSubmitKey()
                && !op.hasAutoRenewPeriod()
                && !op.hasAutoRenewAccount();
    }

    /**
     * Given the appropriate context, updates a topic.
     *
     * @param handleContext the {@link HandleContext} for the active transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);

        final var txn = handleContext.body();
        final var op = txn.consensusUpdateTopicOrThrow();

        final var topicStore = handleContext.storeFactory().writableStore(WritableTopicStore.class);
        final var topic = topicStore.getTopic(op.topicIDOrElse(TopicID.DEFAULT));
        // preHandle already checks for topic existence, so topic should never be null.

        // First validate this topic is mutable; and the pending mutations are allowed
        if (wantsToMutateNonExpiryField(op)) {
            validateTrue(topic.hasAdminKey(), UNAUTHORIZED);
            final var opRemovesAutoRenewId =
                    op.hasAutoRenewAccount() && designatesAccountRemoval(op.autoRenewAccount());
            if (!opRemovesAutoRenewId && topic.hasAutoRenewAccountId()) {
                validateFalse(
                        !topic.hasAdminKey() || (op.hasAdminKey() && isEmpty(op.adminKey())),
                        AUTORENEW_ACCOUNT_NOT_ALLOWED);
            }
        }

        validateMaybeNewAttributes(handleContext, op, topic);

        // Now we apply the mutations to a builder
        final var builder = topic.copyBuilder();
        final var currentMeta =
                new ExpiryMeta(topic.expirationSecond(), topic.autoRenewPeriod(), topic.autoRenewAccountId());
        resolveMutableBuilderAttributes(handleContext, op, builder, currentMeta);
        topicStore.put(builder.build());
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body();
        final var topicUpdate = op.consensusUpdateTopicOrElse(ConsensusUpdateTopicTransactionBody.DEFAULT);
        final var topicId = topicUpdate.topicIDOrElse(TopicID.DEFAULT);
        final var topic = feeContext.readableStore(ReadableTopicStore.class).getTopic(topicId);

        final var oldFees = feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGivenExplicit(op, sigValueObj, topic));
        if(feeContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {

            EntityCreate entity = FeesHelper.makeEntity(HederaFunctionality.CONSENSUS_UPDATE_TOPIC, "Update a topic", 0, false);
            Map<String, Object> params = new HashMap<>();
            params.put("numSignatures", feeContext.numTxnSignatures());
            params.put("numKeys", 0);
            params.put("hasCustomFee", YesOrNo.NO);
            FeeResult simpleFee = entity.computeFee(params);
            return new Fees(oldFees.nodeFee(), 0, oldFees.serviceFee(), simpleFee.fee, simpleFee.details);
        } else {
            return oldFees;
        }
    }

    private void resolveMutableBuilderAttributes(
            @NonNull final HandleContext handleContext,
            @NonNull final ConsensusUpdateTopicTransactionBody op,
            @NonNull final Topic.Builder builder,
            @NonNull final ExpiryMeta currentExpiryMeta) {

        if (op.hasAdminKey()) {
            var key = op.adminKey();
            // Empty key list is allowed and is used for immutable entities (e.g. system accounts)
            if (isImmutableKey(key)) {
                builder.adminKey((Key) null);
            } else {
                builder.adminKey(key);
            }
        }
        if (op.hasSubmitKey()) {
            final var newSubmitKey = op.submitKeyOrThrow();
            if (isKeyRemoval(newSubmitKey)) {
                builder.submitKey((Key) null);
            } else {
                builder.submitKey(newSubmitKey);
            }
        }
        if (op.hasMemo()) {
            builder.memo(op.memo());
        }
        final var resolvedExpiryMeta = resolvedUpdateMetaFrom(handleContext.expiryValidator(), op, currentExpiryMeta);
        builder.expirationSecond(resolvedExpiryMeta.expiry());
        builder.autoRenewPeriod(resolvedExpiryMeta.autoRenewPeriod());
        if (op.hasAutoRenewAccount() && designatesAccountRemoval(op.autoRenewAccount())) {
            builder.autoRenewAccountId((AccountID) null);
        } else {
            builder.autoRenewAccountId(resolvedExpiryMeta.autoRenewAccountId());
        }
        if (op.hasFeeScheduleKey()) {
            final var newFeeScheduleKey = op.feeScheduleKey();
            if (isKeyRemoval(newFeeScheduleKey)) {
                builder.feeScheduleKey((Key) null);
            } else {
                builder.feeScheduleKey(newFeeScheduleKey);
            }
        }
        if (op.hasFeeExemptKeyList()) {
            builder.feeExemptKeyList(op.feeExemptKeyList().keys());
        }
        if (op.hasCustomFees()) {
            builder.customFees(op.customFees().fees());
        }
    }

    private void validateMaybeNewAttributes(
            @NonNull final HandleContext handleContext,
            @NonNull final ConsensusUpdateTopicTransactionBody op,
            @NonNull final Topic topic) {
        final var attributeValidator = handleContext.attributeValidator();
        validateMaybeNewAdminKey(attributeValidator, op);
        validateMaybeNewSubmitKey(attributeValidator, op);
        validateMaybeNewMemo(attributeValidator, op);
        validateMaybeNewFeeScheduleKey(attributeValidator, op, topic);
        validateMaybeFeeExemptKeyList(handleContext, attributeValidator, op);
        validateMaybeCustomFees(handleContext, op);
    }

    private ExpiryMeta resolvedUpdateMetaFrom(
            @NonNull final ExpiryValidator expiryValidator,
            @NonNull final ConsensusUpdateTopicTransactionBody op,
            @NonNull final ExpiryMeta currentMeta) {
        if (updatesExpiryMeta(op)) {
            final var updateMeta = new ExpiryMeta(effExpiryOf(op), effAutoRenewPeriodOf(op), op.autoRenewAccount());
            try {
                return expiryValidator.resolveUpdateAttempt(currentMeta, updateMeta);
            } catch (final HandleException e) {
                if (e.getStatus() == INVALID_RENEWAL_PERIOD) {
                    // Tokens throw INVALID_EXPIRATION_TIME, but for topic it's expected currently to throw
                    // AUTORENEW_DURATION_NOT_IN_RANGE
                    // future('8906')
                    throw new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE);
                }
                throw e;
            }
        } else {
            return currentMeta;
        }
    }

    private long effExpiryOf(@NonNull final ConsensusUpdateTopicTransactionBody op) {
        return op.hasExpirationTime() ? op.expirationTimeOrThrow().seconds() : NA;
    }

    private long effAutoRenewPeriodOf(@NonNull final ConsensusUpdateTopicTransactionBody op) {
        return op.hasAutoRenewPeriod() ? op.autoRenewPeriodOrThrow().seconds() : NA;
    }

    private boolean updatesExpiryMeta(@NonNull final ConsensusUpdateTopicTransactionBody op) {
        return op.expirationTime() != null || op.autoRenewPeriod() != null || op.autoRenewAccount() != null;
    }

    private void validateMaybeNewMemo(
            @NonNull final AttributeValidator attributeValidator,
            @NonNull final ConsensusUpdateTopicTransactionBody op) {
        if (op.hasMemo()) {
            attributeValidator.validateMemo(op.memo());
        }
    }

    private void validateMaybeNewAdminKey(
            @NonNull final AttributeValidator attributeValidator,
            @NonNull final ConsensusUpdateTopicTransactionBody op) {
        // Empty key list is allowed and is used for immutable entities (e.g. system accounts)
        if (op.hasAdminKey() && !isImmutableKey(op.adminKey())) {
            attributeValidator.validateKey(op.adminKey());
        }
    }

    private void validateMaybeNewSubmitKey(
            @NonNull final AttributeValidator attributeValidator,
            @NonNull final ConsensusUpdateTopicTransactionBody op) {
        if (op.hasSubmitKey()) {
            final var newSubmitKey = op.submitKeyOrThrow();
            // Backward compatibility, mono-service allowed setting an empty submit key
            if (!isEmpty(newSubmitKey) || Key.DEFAULT.equals(newSubmitKey)) {
                attributeValidator.validateKey(op.submitKeyOrThrow());
            }
        }
    }

    private void validateMaybeNewFeeScheduleKey(
            @NonNull final AttributeValidator attributeValidator,
            @NonNull final ConsensusUpdateTopicTransactionBody op,
            @NonNull final Topic topic) {
        if (op.hasFeeScheduleKey()) {
            validateTrue(topic.hasFeeScheduleKey(), FEE_SCHEDULE_KEY_CANNOT_BE_UPDATED);
            if (!isEmpty(op.feeScheduleKey())) {
                attributeValidator.validateKey(op.feeScheduleKey(), INVALID_CUSTOM_FEE_SCHEDULE_KEY);
            }
        }
    }

    private void validateMaybeFeeExemptKeyList(
            @NonNull final HandleContext handleContext,
            @NonNull final AttributeValidator attributeValidator,
            @NonNull final ConsensusUpdateTopicTransactionBody op) {
        if (!op.hasFeeExemptKeyList()) {
            return;
        }

        final var configuration = handleContext.configuration();
        final var topicConfig = configuration.getConfigData(TopicsConfig.class);

        validateTrue(
                op.feeExemptKeyList().keys().size() <= topicConfig.maxEntriesForFeeExemptKeyList(),
                MAX_ENTRIES_FOR_FEE_EXEMPT_KEY_LIST_EXCEEDED);
        op.feeExemptKeyList()
                .keys()
                .forEach(key -> attributeValidator.validateKey(key, INVALID_KEY_IN_FEE_EXEMPT_KEY_LIST));
    }

    private void validateMaybeCustomFees(
            @NonNull final HandleContext handleContext, @NonNull final ConsensusUpdateTopicTransactionBody op) {
        if (!op.hasCustomFees()) {
            return;
        }

        final var configuration = handleContext.configuration();
        final var topicConfig = configuration.getConfigData(TopicsConfig.class);
        final var accountStore = handleContext.storeFactory().readableStore(ReadableAccountStore.class);
        final var tokenStore = handleContext.storeFactory().readableStore(ReadableTokenStore.class);
        final var tokenRelStore = handleContext.storeFactory().readableStore(ReadableTokenRelationStore.class);

        validateTrue(
                op.customFees().fees().size() <= topicConfig.maxCustomFeeEntriesForTopics(), CUSTOM_FEES_LIST_TOO_LONG);
        customFeesValidator.validate(
                accountStore, tokenRelStore, tokenStore, op.customFees().fees(), handleContext.expiryValidator());
    }

    /**
     * @param op the transaction body of consensus update operation
     * @return {@code true} if the operation wants to update a non-expiry field, {@code false} otherwise.
     */
    public static boolean wantsToMutateNonExpiryField(@NonNull final ConsensusUpdateTopicTransactionBody op) {
        return op.hasMemo()
                || op.hasAdminKey()
                || op.hasSubmitKey()
                || op.hasAutoRenewPeriod()
                || op.hasAutoRenewAccount();
    }

    private boolean designatesAccountRemoval(AccountID id) {
        return id.hasAccountNum() && id.accountNum() == 0 && id.alias() == null;
    }

    private FeeData usageGivenExplicit(
            @NonNull final TransactionBody txnBody, @NonNull final SigValueObj sigUsage, @Nullable final Topic topic) {
        long rbsIncrease = 0;
        final var protoTxnBody = CommonPbjConverters.fromPbj(txnBody);
        if (topic != null && topic.hasAdminKey()) {
            final var expiry =
                    Timestamp.newBuilder().setSeconds(topic.expirationSecond()).build();
            try {
                rbsIncrease = getUpdateTopicRbsIncrease(
                        protoTxnBody.getTransactionID().getTransactionValidStart(),
                        CommonPbjConverters.fromPbj(topic.adminKeyOrElse(Key.DEFAULT)),
                        CommonPbjConverters.fromPbj(topic.submitKeyOrElse(Key.DEFAULT)),
                        topic.memo(),
                        topic.hasAutoRenewAccountId(),
                        expiry,
                        protoTxnBody.getConsensusUpdateTopic());
            } catch (Exception e) {
                log.warn("Usage estimation unexpectedly failed for {}!", txnBody, e);
            }
        }
        return getConsensusUpdateTopicFee(protoTxnBody, rbsIncrease, sigUsage);
    }
}
