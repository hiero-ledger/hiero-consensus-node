// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_ACCOUNT_ID_IN_MAX_CUSTOM_FEE_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_DENOMINATION_IN_MAX_CUSTOM_FEE_LIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CHUNK_NUMBER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CHUNK_TRANSACTION_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SUBMIT_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_MESSAGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CUSTOM_FEE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_VALID_MAX_CUSTOM_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.LONG_SIZE;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.RECEIPT_STORAGE_TIME_SEC;
import static com.hedera.node.app.hapi.utils.fee.FeeBuilder.TX_HASH_SIZE;
import static com.hedera.node.app.service.consensus.impl.handlers.ConsensusCreateTopicHandler.feeResultToFees;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.DispatchOptions.stepDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.TRANSACTION_FIXED_FEE;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.SUPPRESSING_SIGNED_TX_CUSTOMIZER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.hapi.node.transaction.CustomFeeLimit;
import com.hedera.hapi.node.transaction.FixedCustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.fees.calc.OverflowCheckingCalc;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.service.consensus.impl.WritableTopicStore;
import com.hedera.node.app.service.consensus.impl.handlers.customfee.ConsensusCustomFeeAssessor;
import com.hedera.node.app.service.consensus.impl.records.ConsensusSubmitMessageStreamBuilder;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.support.fees.Extra;

/**
 * This class contains all workflow-related functionality regarding
 * {@link HederaFunctionality#CONSENSUS_SUBMIT_MESSAGE}.
 */
@Singleton
public class ConsensusSubmitMessageHandler implements TransactionHandler {
    private static final int LARGE_STEP_BYTES = 512;
    private static final int UNITS_PER_LARGE_STEP = 10;
    private static final int SMALL_STEP_BYTES = 50;
    /**
     * Running hash version
     */
    public static final long RUNNING_HASH_VERSION = 3L;

    private final ConsensusCustomFeeAssessor customFeeAssessor;

    /**
     * Default constructor for injection.
     */
    @Inject
    public ConsensusSubmitMessageHandler(@NonNull ConsensusCustomFeeAssessor customFeeAssessor) {
        this.customFeeAssessor = requireNonNull(customFeeAssessor);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final TransactionBody txn = context.body();
        final ConsensusSubmitMessageTransactionBody op = txn.consensusSubmitMessageOrThrow();
        // Validate the duplication of payer custom fee limits
        validateDuplicationFeeLimits(txn.maxCustomFees());
        validateTruePreCheck(op.hasTopicID(), INVALID_TOPIC_ID);
        validateFalsePreCheck(op.message().length() == 0, INVALID_TOPIC_MESSAGE);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);

        final var op = context.body().consensusSubmitMessageOrThrow();
        final var topicStore = context.createStore(ReadableTopicStore.class);
        // The topic ID must be present on the transaction and the topic must exist.
        final var topic = topicStore.getTopic(op.topicIDOrElse(TopicID.DEFAULT));
        mustExist(topic, INVALID_TOPIC_ID);
        validateFalsePreCheck(topic.deleted(), INVALID_TOPIC_ID);
        // If a submit key is specified on the topic, then only those transactions signed by that key can be
        // submitted to the topic. If there is no submit key, then it is not required on the transaction.
        if (topic.hasSubmitKey()) {
            context.requireKeyOrThrow(topic.submitKeyOrThrow(), INVALID_SUBMIT_KEY);
        }
    }

    /**
     * Given the appropriate context, submits a message to a topic.
     *
     * @param handleContext the {@link HandleContext} for the active transaction
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Override
    public void handle(@NonNull final HandleContext handleContext) {
        requireNonNull(handleContext);

        final var txn = handleContext.body();
        final var op = txn.consensusSubmitMessageOrThrow();

        final var topicStore = handleContext.storeFactory().writableStore(WritableTopicStore.class);
        final var topic = topicStore.get(op.topicIDOrElse(TopicID.DEFAULT));
        // preHandle already checks for topic existence, so topic should never be null.

        /* Validate all needed fields in the transaction */
        final var config = handleContext.configuration().getConfigData(ConsensusConfig.class);
        validateTransaction(txn, config, topic);

        /* handle custom fees */
        if (!topic.customFees().isEmpty() && !isFeeExempted(topic.feeExemptKeyList(), handleContext.keyVerifier())) {
            // 1. Filter the fee list (exclude fees with payer as fee collector or token treasury)
            final var feesToBeCharged = extractFeesToBeCharged(topic.customFees(), handleContext);
            // 2. Validate payer's fee limits or throw
            if (!txn.maxCustomFees().isEmpty()) {
                validateFeeLimits(handleContext.payer(), feesToBeCharged, txn.maxCustomFees());
            }
            // 3. Charge the fees
            chargeCustomFees(feesToBeCharged, handleContext);
        }

        try {
            final var updatedTopic = updateRunningHashAndSequenceNumber(txn, topic, handleContext.consensusNow());

            /* --- Put the modified topic. It will be in underlying state's modifications map.
            It will not be committed to state until commit is called on the state.--- */
            topicStore.put(updatedTopic);

            handleContext
                    .savepointStack()
                    .getBaseBuilder(ConsensusSubmitMessageStreamBuilder.class)
                    .topicRunningHash(updatedTopic.runningHash())
                    .topicSequenceNumber(updatedTopic.sequenceNumber())
                    .topicRunningHashVersion(RUNNING_HASH_VERSION);
        } catch (IOException e) {
            throw new HandleException(INVALID_TRANSACTION);
        }
    }

    /**
     * Validates te transaction body. Throws {@link HandleException} if any of the validations fail.
     *
     * @param txn the {@link TransactionBody} of the active transaction
     * @param config the {@link ConsensusConfig}
     * @param topic the topic to which the message is being submitted
     */
    private void validateTransaction(final TransactionBody txn, final ConsensusConfig config, final Topic topic) {
        final var txnId = txn.transactionID();
        final var payer = txn.transactionIDOrElse(TransactionID.DEFAULT).accountIDOrElse(AccountID.DEFAULT);
        final var op = txn.consensusSubmitMessageOrThrow();

        /* Check if the message submitted is empty */
        // Question do we need this check ?
        final var msgLen = op.message().length();

        /* Check if the message submitted is greater than acceptable size */
        if (msgLen > config.messageMaxBytesAllowed()) {
            throw new HandleException(MESSAGE_SIZE_TOO_LARGE);
        }

        /* Check if the topic exists */
        if (topic == null) {
            throw new HandleException(INVALID_TOPIC_ID);
        }
        // If the message is too large, user will be able to submit the message fragments in chunks
        // Validate if chunk info is correct
        validateChunkInfo(txnId, payer, op);
    }

    /**
     * If the message is too large, user will be able to submit the message fragments in chunks. Validates the chunk
     * info in the transaction body. Throws {@link HandleException} if any of the validations fail.
     *
     * @param txnId the {@link TransactionID} of the active transaction
     * @param payer the {@link AccountID} of the payer
     * @param op the {@link ConsensusSubmitMessageTransactionBody} of the active transaction
     */
    private void validateChunkInfo(
            final TransactionID txnId, final AccountID payer, final ConsensusSubmitMessageTransactionBody op) {
        if (op.hasChunkInfo()) {
            var chunkInfo = op.chunkInfoOrThrow();

            /* Validate chunk number */
            if (!(1 <= chunkInfo.number() && chunkInfo.number() <= chunkInfo.total())) {
                throw new HandleException(INVALID_CHUNK_NUMBER);
            }

            /* Validate the initial chunk transaction payer is the same payer for the current transaction*/
            if (!chunkInfo
                    .initialTransactionIDOrElse(TransactionID.DEFAULT)
                    .accountIDOrElse(AccountID.DEFAULT)
                    .equals(payer)) {
                throw new HandleException(INVALID_CHUNK_TRANSACTION_ID);
            }

            // Validate if the transaction is submitting initial chunk
            // payer in initial transaction Id should be same as payer of the transaction
            if (1 == chunkInfo.number()
                    && !chunkInfo
                            .initialTransactionIDOrElse(TransactionID.DEFAULT)
                            .equals(txnId)) {
                throw new HandleException(INVALID_CHUNK_TRANSACTION_ID);
            }
        }
    }

    /**
     * Updates the running hash and sequence number of the topic.
     *
     * @param txn the {@link TransactionBody} of the active transaction
     * @param topic the topic to which the message is being submitted
     * @param consensusNow the consensus time of the active transaction
     * @return the updated topic
     * @throws IOException if there is an error while updating the running hash
     */
    public Topic updateRunningHashAndSequenceNumber(
            @NonNull final TransactionBody txn, @NonNull final Topic topic, @Nullable Instant consensusNow)
            throws IOException {
        requireNonNull(txn);
        requireNonNull(topic);

        final var submitMessage = txn.consensusSubmitMessageOrThrow();
        final var payer = txn.transactionIDOrElse(TransactionID.DEFAULT).accountIDOrElse(AccountID.DEFAULT);
        final var topicId = submitMessage.topicIDOrElse(TopicID.DEFAULT);
        final var message = CommonPbjConverters.asBytes(submitMessage.message());

        // This line will be uncommented once there is PBJ fix to make copyBuilder() public
        final var topicBuilder = topic.copyBuilder();

        final var effectiveConsensusNow = (consensusNow == null) ? Instant.ofEpochSecond(0) : consensusNow;

        var sequenceNumber = topic.sequenceNumber();
        var runningHash = topic.runningHash();

        final var boas = new ByteArrayOutputStream();
        try (final var out = new ObjectOutputStream(boas)) {
            out.writeObject(CommonPbjConverters.asBytes(runningHash));
            out.writeLong(RUNNING_HASH_VERSION);
            out.writeLong(payer.shardNum());
            out.writeLong(payer.realmNum());
            out.writeLong(payer.accountNumOrElse(0L));
            out.writeLong(topicId.shardNum());
            out.writeLong(topicId.realmNum());
            out.writeLong(topicId.topicNum());
            out.writeLong(effectiveConsensusNow.getEpochSecond());
            out.writeInt(effectiveConsensusNow.getNano());

            /* Update the sequence number */
            topicBuilder.sequenceNumber(++sequenceNumber);

            out.writeLong(sequenceNumber);
            out.writeObject(noThrowSha384HashOf(message));
            out.flush();
            runningHash = Bytes.wrap(noThrowSha384HashOf(boas.toByteArray()));

            /* Update the running hash */
            topicBuilder.runningHash(runningHash);
        }
        return topicBuilder.build();
    }

    /**
     * @param byteArray the byte array to hash
     * @return the byte array of the hashed value
     */
    public static byte[] noThrowSha384HashOf(final byte[] byteArray) {
        try {
            return MessageDigest.getInstance("SHA-384").digest(byteArray);
        } catch (final NoSuchAlgorithmException fatal) {
            throw new IllegalStateException(fatal);
        }
    }

    private void chargeCustomFees(final List<FixedCustomFee> feesToBeCharged, final HandleContext handleContext) {
        // 1. Create synthetic bodies for each fee
        final var syntheticBodies = customFeeAssessor.assessCustomFee(feesToBeCharged, handleContext.payer());
        final var assessedCustomFees = new ArrayList<AssessedCustomFee>();

        // 2. Dispatch each synthetic body
        for (final var entry : syntheticBodies.entrySet()) {
            final var syntheticBody = entry.getValue();
            final var cryptoTransferBody =
                    TransactionBody.newBuilder().cryptoTransfer(syntheticBody).build();
            // dispatch transfers to pay the fees, but suppress any child records.
            final var dispatchedStreamBuilder = handleContext.dispatch(stepDispatch(
                    handleContext.payer(),
                    cryptoTransferBody,
                    CryptoTransferStreamBuilder.class,
                    SUPPRESSING_SIGNED_TX_CUSTOMIZER,
                    new DispatchMetadata(TRANSACTION_FIXED_FEE, entry.getKey())));
            // validate response and collect assessed fees
            validateTrue(dispatchedStreamBuilder.status().equals(SUCCESS), dispatchedStreamBuilder.status());
            assessedCustomFees.addAll(dispatchedStreamBuilder.getAssessedCustomFees());
        }

        // 3. Externalize the assessed fees
        handleContext
                .savepointStack()
                .getBaseBuilder(ConsensusSubmitMessageStreamBuilder.class)
                .assessedCustomFees(assessedCustomFees);
    }

    /**
     * Check if the submit message transaction is fee exempt
     *
     * @param feeExemptKeyList The list of keys that are exempt from fees
     * @param keyVerifier The key verifier of this transaction
     * @return if the transaction is fee exempt
     */
    private boolean isFeeExempted(@NonNull final List<Key> feeExemptKeyList, @NonNull final KeyVerifier keyVerifier) {
        if (!feeExemptKeyList.isEmpty()) {
            final var authorizingKeys =
                    keyVerifier.authorizingSimpleKeys().stream().toList();
            final VerificationAssistant callback =
                    (k, ignore) -> simpleKeyVerifierFrom(authorizingKeys).test(k);
            // check if authorizing keys are satisfying any of the fee exempt keys
            for (final var feeExemptKey : feeExemptKeyList) {
                if (keyVerifier.verificationFor(feeExemptKey, callback).passed()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Predicate<Key> simpleKeyVerifierFrom(@NonNull final List<Key> signatories) {
        final Set<Key> cryptoSigs = new HashSet<>();
        signatories.forEach(k -> {
            switch (k.key().kind()) {
                case ED25519, ECDSA_SECP256K1 -> cryptoSigs.add(k);
                default -> {
                    // No other key type can be a signatory
                }
            }
        });
        return key -> switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1 -> cryptoSigs.contains(key);
            default -> false;
        };
    }

    /**
     * Validate that each topic custom fee has equal or lower value than the payer's limit
     *
     * @param topicCustomFees The topic's custom fee list
     * @param allCustomFeeLimits List with limits of fees that the payer is willing to pay
     */
    private void validateFeeLimits(
            @NonNull final AccountID payer,
            @NonNull final List<FixedCustomFee> topicCustomFees,
            @NonNull final List<CustomFeeLimit> allCustomFeeLimits) {
        // Extract the token fees and hbar fees from the topic custom fees
        Map<TokenID, Long> tokenFees = new HashMap<>();
        AtomicReference<Long> hbarFee = new AtomicReference<>(0L);
        totalAmountToBeCharged(topicCustomFees, hbarFee, tokenFees);
        final var payerLimits = allCustomFeeLimits.stream()
                .filter(maxCustomFee -> payer.equals(maxCustomFee.accountId()))
                .map(CustomFeeLimit::fees)
                .flatMap(List::stream)
                .toList();

        // Validate payer token limits
        tokenFees.forEach((token, feeAmount) -> {
            final boolean isValid = payerLimits.stream()
                    .filter(maxCustomFee -> token.equals(maxCustomFee.denominatingTokenId()))
                    .anyMatch(maxCustomFee -> {
                        validateTrue(maxCustomFee.amount() >= feeAmount, MAX_CUSTOM_FEE_LIMIT_EXCEEDED);
                        return true;
                    });
            validateTrue(isValid, NO_VALID_MAX_CUSTOM_FEE);
        });
        // Validate payer HBAR limit
        if (hbarFee.get() > 0) {
            final var payerHbarLimit = payerLimits.stream()
                    .filter(maxCustomFee -> !maxCustomFee.hasDenominatingTokenId())
                    .findFirst()
                    .orElseThrow(() -> new HandleException(NO_VALID_MAX_CUSTOM_FEE));
            validateTrue(payerHbarLimit.amount() >= hbarFee.get(), MAX_CUSTOM_FEE_LIMIT_EXCEEDED);
        }
    }

    /**
     * Extracts only the fees that are going to be charged.
     * The payer will not be charged in case he is a fee collector or token treasury.
     *
     * @param topicCustomFees All topic custom fees
     * @param context The handle context
     * @return List containing only the fees concerning given payer
     */
    private List<FixedCustomFee> extractFeesToBeCharged(
            @NonNull final List<FixedCustomFee> topicCustomFees, @NonNull final HandleContext context) {
        final var payer = context.payer();
        final var tokenStore = context.storeFactory().readableStore(ReadableTokenStore.class);
        return topicCustomFees.stream()
                .filter(fee -> {
                    var fixedFee = fee.fixedFeeOrThrow();
                    if (payer.equals(fee.feeCollectorAccountId())) {
                        return false;
                    }
                    if (fixedFee.hasDenominatingTokenId()) {
                        final var denomTokenId = fixedFee.denominatingTokenId();
                        final var treasury = customFeeAssessor.getTokenTreasury(denomTokenId, tokenStore);
                        return !payer.equals(treasury);
                    }
                    return true;
                })
                .toList();
    }

    /**
     * Calculate the total amount of fees to be charge per denomination token and hbar fees.
     *
     * @param topicCustomFees All fees to be charged
     * @param hbarFee The total hbar amount.
     * @param tokenFees Map with total amount per token.
     */
    private void totalAmountToBeCharged(
            @NonNull List<FixedCustomFee> topicCustomFees,
            AtomicReference<Long> hbarFee,
            Map<TokenID, Long> tokenFees) {
        for (final var fee : topicCustomFees) {
            var fixedFee = fee.fixedFeeOrThrow();
            if (!fixedFee.hasDenominatingTokenId()) {
                hbarFee.updateAndGet(v -> v + fixedFee.amount());
            } else {
                final var denomTokenId = fixedFee.denominatingTokenId();
                tokenFees.put(denomTokenId, tokenFees.getOrDefault(denomTokenId, 0L) + fixedFee.amount());
            }
        }
    }

    private void validateDuplicationFeeLimits(@NonNull final List<CustomFeeLimit> allCustomFeeLimits)
            throws PreCheckException {
        // Validate that there are no duplicated account ids in the max custom fee list
        final var accounts =
                allCustomFeeLimits.stream().map(CustomFeeLimit::accountId).toList();
        validateTruePreCheck(
                accounts.size() == new HashSet<>(accounts).size(), DUPLICATE_ACCOUNT_ID_IN_MAX_CUSTOM_FEE_LIST);
        // Validate that there are no duplicated denominating token ids in the max custom fee list
        for (final var customFeeLimit : allCustomFeeLimits) {
            final var htsCustomFeeLimits = customFeeLimit.fees().stream()
                    .filter(FixedFee::hasDenominatingTokenId)
                    .toList();
            final var hbarCustomFeeLimits = customFeeLimit.fees().stream()
                    .filter(maxCustomFee -> !maxCustomFee.hasDenominatingTokenId())
                    .toList();

            final var htsLimitHasDuplicate = htsCustomFeeLimits.stream()
                            .map(FixedFee::denominatingTokenId)
                            .collect(Collectors.toSet())
                            .size()
                    != htsCustomFeeLimits.size();
            final var hbarLimitsHasDuplicate = new HashSet<>(hbarCustomFeeLimits).size() != hbarCustomFeeLimits.size();
            validateTruePreCheck(
                    !htsLimitHasDuplicate && !hbarLimitsHasDuplicate, DUPLICATE_DENOMINATION_IN_MAX_CUSTOM_FEE_LIST);
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var op = feeContext.body().consensusSubmitMessageOrThrow();
        final var calculatorFactory = feeContext.feeCalculatorFactory();
        final var msgSize = op.message().length();

        final var topic =
                feeContext.readableStore(ReadableTopicStore.class).getTopic(op.topicIDOrElse(TopicID.DEFAULT));
        final var hasCustomFees = topic != null && !topic.customFees().isEmpty();

        if (feeContext.configuration().getConfigData(FeesConfig.class).simpleFeesEnabled()) {
            final var entity = FeeModelRegistry.lookupModel(HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE);
            Map<Extra, Long> params = new HashMap<>();
            params.put(Extra.BYTES, msgSize);
            params.put(Extra.SIGNATURES, (long) feeContext.numTxnSignatures());
            params.put(Extra.CUSTOM_FEE, 0L);
            if (hasCustomFees) {
                params.put(Extra.CUSTOM_FEE, 5L);
            }
            final var feeResult = entity.computeFee(
                    params,
                    feeContext
                            .feeCalculatorFactory()
                            .feeCalculator(SubType.DEFAULT)
                            .getSimpleFeesSchedule());
            return feeResultToFees(feeResult,fromPbj(feeContext.activeRate()));
        }

        if (hasCustomFees) {
            final var calculator = calculatorFactory.feeCalculator(SubType.SUBMIT_MESSAGE_WITH_CUSTOM_FEES);
            calculator.resetUsage();
            // Charges the canonical price of $0.05 for a message up to 512 bytes and scales up to $0.06 for 1KB
            // The price will scale based on the number of signatures
            final var largePart = Math.max(1, msgSize / LARGE_STEP_BYTES);
            final var remainder = msgSize < LARGE_STEP_BYTES ? 0 : msgSize % LARGE_STEP_BYTES;
            final var feeUnits = SMALL_STEP_BYTES
                    + ((largePart - 1) * UNITS_PER_LARGE_STEP)
                    + (remainder + SMALL_STEP_BYTES - 1) / SMALL_STEP_BYTES;
            return calculator
                    .addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1))
                    .addBytesPerTransaction(feeUnits)
                    .calculate();
        }

        return calculatorFactory
                .feeCalculator(SubType.DEFAULT)
                .addBytesPerTransaction(BASIC_ENTITY_ID_SIZE + msgSize)
                .addNetworkRamByteSeconds((LONG_SIZE + TX_HASH_SIZE) * RECEIPT_STORAGE_TIME_SEC)
                .calculate();
    }
}
