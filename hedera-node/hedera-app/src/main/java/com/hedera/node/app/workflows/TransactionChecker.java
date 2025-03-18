// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_PREFIX_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_HAS_UNKNOWN_FIELDS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_ID_FIELD_NOT_ALLOWED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.CustomFeeLimit;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.annotations.NodeSelfId;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.util.ProtobufUtils;
import com.hedera.node.app.workflows.prehandle.DueDiligenceException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UnknownFieldException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Checks transactions for internal consistency and validity.
 *
 * <p>This is used in every workflow that deals with transactions including the query workflow for paid queries where
 * it checks the attached {@link HederaFunctionality#CRYPTO_TRANSFER} transaction.
 *
 * <p>Transactions have a deprecated set of fields. Deprecation metrics are kept to track their usage.
 *
 * <p>This class is a thread-safe singleton.
 */
@Singleton
public class TransactionChecker {
    private static final Logger logger = LogManager.getLogger(TransactionChecker.class);

    private static final int USER_TRANSACTION_NONCE = 0;
    private static final List<HederaFunctionality> FUNCTIONALITIES_WITH_MAX_CUSTOM_FEES =
            List.of(CONSENSUS_SUBMIT_MESSAGE);

    // Metric config for keeping track of the number of deprecated transactions received
    private static final String COUNTER_DEPRECATED_TXNS_NAME = "DeprTxnsRcv";
    private static final String COUNTER_RECEIVED_DEPRECATED_DESC =
            "number of deprecated txns (bodyBytes, sigMap) received";
    private static final String COUNTER_SUPER_DEPRECATED_TXNS_NAME = "SuperDeprTxnsRcv";
    private static final String COUNTER_RECEIVED_SUPER_DEPRECATED_DESC =
            "number of super-deprecated txns (body, sigs) received";

    /** The {@link Counter} used to track the number of deprecated transactions (bodyBytes, sigMap) received. */
    private final Counter deprecatedCounter;
    /** The {@link Counter} used to track the number of super deprecated transactions (body, sigs) received. */
    private final Counter superDeprecatedCounter;
    /** The account ID of the node running this software */
    private final AccountID nodeAccount;

    private final HederaConfig hederaConfig;
    private final JumboTransactionsConfig jumboTransactionsConfig;

    // TODO We need to incorporate the check for "TRANSACTION_TOO_MANY_LAYERS". "maxProtoMessageDepth" is a property
    //  passed to StructuralPrecheck used for this purpose. We will need to add this to PBJ as an argument to the
    //  parser in strict mode to prevent it from parsing too deep.

    /**
     * Create a new {@link TransactionChecker}
     *
     * @param configProvider access to configuration
     * @param metrics metrics related to workflows
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Inject
    public TransactionChecker(
            @NodeSelfId @NonNull final AccountID nodeAccount,
            @NonNull final ConfigProvider configProvider,
            @NonNull final Metrics metrics) {
        this.nodeAccount = requireNonNull(nodeAccount);
        this.deprecatedCounter = metrics.getOrCreate(new Counter.Config("app", COUNTER_DEPRECATED_TXNS_NAME)
                .withDescription(COUNTER_RECEIVED_DEPRECATED_DESC));
        this.superDeprecatedCounter = metrics.getOrCreate(new Counter.Config("app", COUNTER_SUPER_DEPRECATED_TXNS_NAME)
                .withDescription(COUNTER_RECEIVED_SUPER_DEPRECATED_DESC));

        hederaConfig = configProvider.getConfiguration().getConfigData(HederaConfig.class);
        jumboTransactionsConfig = configProvider.getConfiguration().getConfigData(JumboTransactionsConfig.class);
    }

    /**
     * Parses and checks the transaction encoded as protobuf in the given buffer.
     *
     * @param buffer The buffer containing the protobuf bytes of the transaction
     * @param maxBytes The maximum number of bytes that can exist in the transaction
     * @return The parsed {@link TransactionInfo}
     * @throws PreCheckException If parsing fails or any of the checks fail.
     */
    @NonNull
    public TransactionInfo parseAndCheck(@NonNull final Bytes buffer, final int maxBytes) throws PreCheckException {
        // Fail fast if there are too many transaction bytes
        if (buffer.length() > maxBytes) {
            throw new PreCheckException(TRANSACTION_OVERSIZE);
        }

        final var tx = parseStrict(buffer.toReadableSequentialData(), Transaction.PROTOBUF, INVALID_TRANSACTION);
        return check(tx, buffer);
    }

    /**
     * Check the validity of the provided {@link Transaction}
     *
     * <p>The following checks are made:
     * <ul>
     *   <li>Check that the transaction either uses the deprecated fields, or the new field, but not both</li>
     *   <li>Check that the transaction has a transaction body (either deprecated or non-deprecated field)</li>
     *   <li>If using {@code signedTransactionBytes}, verify that the {@link SignedTransaction} can be parsed</li>
     *   <li>Check that the {@link TransactionBody} can be parsed</li>
     *   <li>Check that the {@code transactionID} is specified</li>
     *   <li>Check that the {@code transactionID} has an accountID that is plausible, meaning that it may exist.</li>
     *   <li>Check that the {@code transactionID} does not have the "scheduled" flag set</li>
     *   <li>Check that the {@code transactionID} does not have a nonce set</li>
     *   <li>Check that this transaction is still live (i.e. its timestamp is within the last 3 minutes).</li>
     *   <li>Check that the {@code memo} is not too large</li>
     *   <li>Check that the {@code transaction fee} is non-zero</li>
     * </ul>
     *
     * <p>In all cases involving parsing, parse <strong>strictly</strong>, meaning, if there are any fields in the
     * protobuf that we do not understand, then throw a {@link PreCheckException}. This means that we are *NOT*
     * forward compatible. You cannot send a protobuf encoded object to any of the workflows that is newer than the
     * version of software that is running.
     *
     * <p>As can be seen from the above list, these checks are verifying that the transaction is internally consistent,
     * rather than comparing with state, OTHER THAN deduplication. The account on the transaction may not actually
     * exist, or may not have enough balance, or the transaction may not have paid enough to cover the fees, or many
     * other scenarios. Those will be checked in later stages of the workflow (and in many cases, within the service
     * modules themselves).</p>
     *
     * @param tx the {@link Transaction} that needs to be checked
     * @param serializedTx if set, the serialized transaction bytes to include in the {@link TransactionInfo}
     * @return an {@link TransactionInfo} with the parsed and checked entities
     * @throws PreCheckException if the data is not valid
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @NonNull
    @VisibleForTesting
    TransactionInfo check(@NonNull final Transaction tx, @NonNull Bytes serializedTx) throws PreCheckException {
        // NOTE: Since we've already parsed the transaction, we assume that the
        // transaction was not too many bytes. This is a safe assumption because
        // the code that receives the transaction bytes and parses/ the transaction
        // also verifies that the transaction is not too large.
        checkTransactionDeprecation(tx);

        final TransactionBody txBody;
        final Bytes bodyBytes;
        final SignatureMap signatureMap;
        if (tx.hasBody()) {
            txBody = tx.bodyOrThrow();
            try {
                bodyBytes = ProtobufUtils.extractBodyBytes(serializedTx);
            } catch (IOException | ParseException e) {
                throw new PreCheckException(INVALID_TRANSACTION);
            }
            signatureMap = tx.sigMap();
        } else {
            if (tx.signedTransactionBytes().length() > 0) {
                final var signedTransaction = parseStrict(
                        tx.signedTransactionBytes().toReadableSequentialData(),
                        SignedTransaction.PROTOBUF,
                        INVALID_TRANSACTION);
                bodyBytes = signedTransaction.bodyBytes();
                signatureMap = signedTransaction.sigMap();
            } else {
                bodyBytes = tx.bodyBytes();
                signatureMap = tx.sigMap();
            }
            txBody = parseStrict(
                    bodyBytes.toReadableSequentialData(), TransactionBody.PROTOBUF, INVALID_TRANSACTION_BODY);
        }
        if (signatureMap == null) {
            throw new PreCheckException(INVALID_TRANSACTION_BODY);
        }
        final HederaFunctionality functionality;
        try {
            functionality = HapiUtils.functionOf(txBody);
        } catch (UnknownHederaFunctionality e) {
            throw new PreCheckException(INVALID_TRANSACTION_BODY);
        }
        if (!txBody.hasTransactionID()) {
            throw new PreCheckException(INVALID_TRANSACTION_ID);
        } else {
            final var txnId = txBody.transactionIDOrThrow();
            if (!txnId.hasAccountID()) {
                throw new PreCheckException(PAYER_ACCOUNT_NOT_FOUND);
            }
        }
        return checkParsed(new TransactionInfo(tx, txBody, signatureMap, bodyBytes, functionality, serializedTx));
    }

    public TransactionInfo checkParsed(@NonNull final TransactionInfo txInfo) throws PreCheckException {
        try {
            checkPrefixMismatch(txInfo.signatureMap().sigPair());
            checkTransactionBody(txInfo.txBody(), txInfo.functionality());
            checkJumboTransactionBody(txInfo);
            return txInfo;
        } catch (PreCheckException e) {
            throw new DueDiligenceException(e.responseCode(), txInfo);
        }
    }

    /**
     * Verifies that a transaction is either using deprecated fields, or not, but not both. If it is using
     * deprecated fields, then increment associated metrics.
     *
     * @param tx the {@code Transaction}
     * @throws PreCheckException If the transaction is using both deprecated and non-deprecated fields
     * @throws NullPointerException if {@code tx} is {@code null}
     */
    private void checkTransactionDeprecation(@NonNull final Transaction tx) throws PreCheckException {
        // There are three ways a transaction can be used. Two of these are deprecated:
        //   1. body & sigMap. SUPPORTED
        //   2. bodyBytes & sigMap. DEPRECATED, SUPPORTED
        //   3. signedTransactionBytes. DEPRECATED, SUPPORTED
        //
        // While Transaction.sigs is not supported, we also don't throw an error if it is used as long as sigMap
        // is also populated. This seems really odd, and ideally we would be able to remove support for sigs entirely.
        // To do this, we need metrics to see if anyone is using it in any way.
        if (tx.hasSigs()) {
            superDeprecatedCounter.increment();
        }

        // A transaction can either use signedTransactionBytes, or sigMap and body/bodyBytes.
        // The usage of signedTransactionBytes and bodyBytes is deprecated.
        final var hasBody = tx.hasBody();
        final var hasSigMap = tx.hasSigMap();
        final var hasDeprecatedSignedTxnBytes = tx.signedTransactionBytes().length() > 0;
        final var hasDeprecatedBodyBytes = tx.bodyBytes().length() > 0;

        // Increment the counter if either of `bodyBytes` or `signedTransactionBytes` were used
        if (hasDeprecatedSignedTxnBytes || hasDeprecatedBodyBytes) {
            deprecatedCounter.increment();
        }

        // The user either has to use `signedTransactionBytes`, or `body`/`bodyBytes` with `sigMap`, but no other
        // combination
        if ((hasBody && hasDeprecatedBodyBytes)
                || hasDeprecatedSignedTxnBytes && (hasBody || hasSigMap || hasDeprecatedBodyBytes)) {
            throw new PreCheckException(INVALID_TRANSACTION);
        }
        if (!hasBody && !hasDeprecatedBodyBytes && !hasDeprecatedSignedTxnBytes) {
            throw new PreCheckException(INVALID_TRANSACTION_BODY);
        }
    }

    /**
     * Validates a {@link TransactionBody}
     *
     * @param txBody the {@link TransactionBody} to check
     * @throws PreCheckException if validation fails
     * @throws NullPointerException if any of the parameters is {@code null}
     */
    private void checkTransactionBody(@NonNull final TransactionBody txBody, HederaFunctionality functionality)
            throws PreCheckException {
        checkTransactionID(txBody.transactionIDOrThrow());
        checkMemo(txBody.memo(), hederaConfig.transactionMaxMemoUtf8Bytes());
        checkMaxCustomFee(txBody.maxCustomFees(), functionality);

        // You cannot have a negative transaction fee!! We're not paying you, buddy.
        if (txBody.transactionFee() < 0) {
            throw new PreCheckException(INSUFFICIENT_TX_FEE);
        }

        if (!txBody.hasTransactionValidDuration()) {
            throw new PreCheckException(INVALID_TRANSACTION_DURATION);
        }
    }

    @VisibleForTesting
    void checkJumboTransactionBody(TransactionInfo txInfo) throws PreCheckException {
        final var jumboTxnEnabled = jumboTransactionsConfig.isEnabled();
        final var allowedJumboHederaFunctionalities = jumboTransactionsConfig.allowedHederaFunctionalities();
        final var maxJumboEthereumCallDataSize = jumboTransactionsConfig.ethereumMaxCallDataSize();

        if (jumboTxnEnabled
                && txInfo.serializedTransaction().length() > hederaConfig.transactionMaxBytes()
                && !allowedJumboHederaFunctionalities.contains(fromPbj(txInfo.functionality()))) {
            throw new PreCheckException(TRANSACTION_OVERSIZE);
        }

        if (txInfo.txBody() != null
                && txInfo.txBody().hasEthereumTransaction()
                && txInfo.txBody().ethereumTransaction().ethereumData().length() > maxJumboEthereumCallDataSize) {
            throw new PreCheckException(TRANSACTION_OVERSIZE);
        }
    }

    public enum RequireMinValidLifetimeBuffer {
        YES,
        NO
    }

    /**
     * Checks whether the transaction duration is valid as per the configuration for valid durations
     * for the network, and whether the current node wall-clock time falls between the transaction
     * start and the transaction end (transaction start + duration).
     *
     * @param txBody The transaction body that needs to be checked.
     * @param consensusTime The consensus time used for comparison (either exact or an approximation)
     * @param requireMinValidLifetimeBuffer Whether to require a minimum valid lifetime buffer
     * @throws PreCheckException if the transaction duration is invalid, or if the start time is too old, or in the future.
     */
    public void checkTimeBox(
            @NonNull final TransactionBody txBody,
            @NonNull final Instant consensusTime,
            @NonNull final RequireMinValidLifetimeBuffer requireMinValidLifetimeBuffer)
            throws PreCheckException {
        requireNonNull(txBody, "txBody must not be null");

        // At this stage the txBody should have been checked already. We simply throw if a mandatory field is missing.
        final var start = txBody.transactionIDOrThrow().transactionValidStartOrThrow();
        final var duration = txBody.transactionValidDurationOrThrow();

        // Get the configured boundaries
        final var min = hederaConfig.transactionMinValidDuration();
        final var max = hederaConfig.transactionMaxValidDuration();
        final var minValidityBufferSecs = requireMinValidLifetimeBuffer == RequireMinValidLifetimeBuffer.YES
                ? hederaConfig.transactionMinValidityBufferSecs()
                : 0;

        // The transaction duration must not be longer than the configured maximum transaction duration
        // or less than the configured minimum transaction duration.
        final var validForSecs = duration.seconds();
        if (validForSecs < min || validForSecs > max) {
            throw new PreCheckException(INVALID_TRANSACTION_DURATION);
        }

        final var validStart = toInstant(start);
        final var validDuration = toSecondsDuration(validForSecs, validStart, minValidityBufferSecs);
        if (validStart.plusSeconds(validDuration).isBefore(consensusTime)) {
            throw new PreCheckException(TRANSACTION_EXPIRED);
        }
        if (validStart.isAfter(consensusTime)) {
            throw new PreCheckException(INVALID_TRANSACTION_START);
        }
    }

    /**
     * Validates a {@link TransactionID}
     *
     * @param txnId the {@link TransactionID} to check
     * @throws PreCheckException if validation fails
     * @throws NullPointerException if any of the parameters is {@code null}
     */
    private void checkTransactionID(@NonNull final TransactionID txnId) throws PreCheckException {
        // Determines whether the given {@link AccountID} can possibly be valid. This method does not refer to state,
        // it simply looks at the {@code accountID} itself to determine whether it might be valid. An ID is valid if
        // the shard and realm match the shard and realm of this node, AND if the account number is positive
        // alias payer account is not allowed to submit transactions.
        final var accountID = txnId.accountID();
        final var isPlausibleAccount = accountID != null
                && accountID.shardNum() == nodeAccount.shardNum()
                && accountID.realmNum() == nodeAccount.realmNum()
                && accountID.hasAccountNum()
                && accountID.accountNumOrElse(0L) > 0;

        if (!isPlausibleAccount) {
            throw new PreCheckException(PAYER_ACCOUNT_NOT_FOUND);
        }

        if (txnId.scheduled() || txnId.nonce() != USER_TRANSACTION_NONCE) {
            throw new PreCheckException(TRANSACTION_ID_FIELD_NOT_ALLOWED);
        }

        if (!txnId.hasTransactionValidStart()) {
            throw new PreCheckException(INVALID_TRANSACTION_START);
        }
    }

    /**
     * Checks whether the memo passes checks.
     *
     * @param memo The memo to check.
     * @throws PreCheckException if the memo is too long, or otherwise fails the check.
     */
    private void checkMemo(@Nullable final String memo, final int maxMemoUtf8Bytes) throws PreCheckException {
        if (memo == null) return; // Nothing to do, a null memo is valid.
        // Verify the number of bytes does not exceed the maximum allowed.
        // Note that these bytes are counted in UTF-8.
        final var buffer = memo.getBytes(StandardCharsets.UTF_8);
        if (buffer.length > maxMemoUtf8Bytes) {
            throw new PreCheckException(MEMO_TOO_LONG);
        }
        // FUTURE: This check should be removed after mirror node supports 0x00 in memo fields
        for (final byte b : buffer) {
            if (b == 0) {
                throw new PreCheckException(INVALID_ZERO_BYTE_IN_STRING);
            }
        }
    }

    private void checkMaxCustomFee(List<CustomFeeLimit> maxCustomFeeList, HederaFunctionality functionality)
            throws PreCheckException {
        if (!FUNCTIONALITIES_WITH_MAX_CUSTOM_FEES.contains(functionality) && !maxCustomFeeList.isEmpty()) {
            throw new PreCheckException(ResponseCodeEnum.MAX_CUSTOM_FEES_IS_NOT_SUPPORTED);
        }

        // check required fields
        for (var maxCustomFee : maxCustomFeeList) {
            if (maxCustomFee.accountId() == null || maxCustomFee.fees().isEmpty()) {
                throw new PreCheckException(ResponseCodeEnum.INVALID_MAX_CUSTOM_FEES);
            }
            for (final var fee : maxCustomFee.fees()) {
                if (fee.amount() < 0) {
                    throw new PreCheckException(ResponseCodeEnum.INVALID_MAX_CUSTOM_FEES);
                }
            }
        }
    }

    /**
     * This method converts a {@link Timestamp} to an {@link Instant} limited between {@link Instant#MIN} and
     * {@link Instant#MAX}
     *
     * @param timestamp the {@code Timestamp} that should be converted
     * @return the resulting {@code Instant}
     */
    @NonNull
    private Instant toInstant(final Timestamp timestamp) {
        return Instant.ofEpochSecond(
                Math.clamp(timestamp.seconds(), Instant.MIN.getEpochSecond(), Instant.MAX.getEpochSecond()),
                Math.clamp(timestamp.nanos(), Instant.MIN.getNano(), Instant.MAX.getNano()));
    }

    /**
     * This method calculates the valid duration given in seconds, which is the provided number of seconds minus a
     * buffer defined in system configuration. The result is limited to a value that, if added to the
     * {@code validStart}, will not exceed {@link Instant#MAX}.
     *
     * @param validForSecs the duration in seconds
     * @param validStart the {@link Instant} that is used to calculate the maximum
     * @param minValidBufferSecs the minimum buffer in seconds
     * @return the valid duration given in seconds
     */
    private long toSecondsDuration(final long validForSecs, final Instant validStart, final long minValidBufferSecs) {
        return Math.min(validForSecs - minValidBufferSecs, Instant.MAX.getEpochSecond() - validStart.getEpochSecond());
    }

    /**
     * A utility method for strictly parsing a protobuf message, throwing {@link PreCheckException} if the message
     * is malformed or contains unknown fields.
     *
     * @param data The protobuf data to parse.
     * @param codec The codec to use for parsing
     * @param parseErrorCode The error code to use if the data is malformed or contains unknown fields.
     * @param <T> The type of the message to parseStrict.
     * @return The parsed message.
     * @throws PreCheckException if the data is malformed or contains unknown fields.
     */
    @NonNull
    private <T> T parseStrict(@NonNull ReadableSequentialData data, Codec<T> codec, ResponseCodeEnum parseErrorCode)
            throws PreCheckException {
        try {
            return codec.parseStrict(data);
        } catch (ParseException e) {
            if (e.getCause() instanceof UnknownFieldException) {
                // We do not allow newer clients to send transactions to older networks.
                throw new PreCheckException(TRANSACTION_HAS_UNKNOWN_FIELDS);
            }

            // Either the protobuf was malformed, or something else failed during parsing
            logger.warn("ParseException while parsing protobuf", e);
            throw new PreCheckException(parseErrorCode);
        }
    }

    /**
     *  We must throw KEY_PREFIX_MISMATCH if the same prefix shows up more than once in the signature map. We
     *  could check for that if we sort the keys by prefix first. Then we can march through them and if we find any
     *  duplicates then we throw KEY_PREFIX_MISMATCH. We must also throw KEY_PREFIX_MISMATCH if the prefix of one
     *  entry is the prefix of another entry (i.e. during key matching, if it would be possible for a single key to
     *  match multiple entries, then we throw).
     *
     * @param sigPairs The list of signature pairs to check. Cannot be null.
     * @throws PreCheckException if the list contains duplicate prefixes or prefixes that could apply to the same key
     */
    private void checkPrefixMismatch(@NonNull final List<SignaturePair> sigPairs) throws PreCheckException {
        final var sortedList = sort(sigPairs);
        if (sortedList.size() > 1) {
            var prev = sortedList.get(0);
            var size = sortedList.size();
            for (int i = 1; i < size; i++) {
                final var curr = sortedList.get(i);
                final var p1 = prev.pubKeyPrefix();
                final var p2 = curr.pubKeyPrefix();
                // NOTE: Length equality check is a workaround for a bug in Bytes in PBJ
                if ((p1.length() == 0 && p2.length() == 0) || p2.matchesPrefix(p1)) {
                    throw new PreCheckException(KEY_PREFIX_MISMATCH);
                }
                prev = curr;
            }
        }
    }

    /**
     * Sorts the list of signature pairs by the prefix of the public key. Sort them such that shorter prefixes come
     * before longer prefixes, and if two prefixes are the same length then sort them lexicographically (lower bytes
     * before higher bytes).
     *
     * @param sigPairs The list of signature pairs to sort. Cannot be null.
     * @return the sorted list of signature pairs
     */
    @NonNull
    private List<SignaturePair> sort(@NonNull final List<SignaturePair> sigPairs) {
        final var sortedList = new ArrayList<>(sigPairs);
        sortedList.sort((s1, s2) -> {
            final var p1 = s1.pubKeyPrefix();
            final var p2 = s2.pubKeyPrefix();
            if (p1.length() != p2.length()) {
                return (int) (p1.length() - p2.length());
            }

            for (int i = 0; i < p1.length(); i++) {
                final var b1 = p1.getByte(i);
                final var b2 = p2.getByte(i);
                if (b1 != b2) {
                    return b1 - b2;
                }
            }

            return 0;
        });
        return sortedList;
    }
}
