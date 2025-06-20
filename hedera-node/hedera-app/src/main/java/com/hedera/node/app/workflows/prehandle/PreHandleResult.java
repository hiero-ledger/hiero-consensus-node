// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.workflows.prehandle.PreHandleResult.Status.NODE_DUE_DILIGENCE_FAILURE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Metadata collected when transactions are handled as part of "pre-handle". This is then made available to the
 * handle workflow.
 *
 * @param payer payer for the transaction, which could be from the transaction body, or could be a node account.
 *              The payer **may be null** only in the event of a catastrophic unknown exception. That is, if the
 *              status is {@link Status#UNKNOWN_FAILURE}, then the payer will be null. If the status is
 *              {@link Status#NODE_DUE_DILIGENCE_FAILURE}, then the payer will be the node account. In all other cases,
 *              the payer is extracted from the transaction body.
 * @param payerKey The cryptographic key of the payer. This will be {@code null} if the payer is {@code null}.
 * @param status {@link Status} of this pre-handle. Will always be set.
 * @param responseCode {@link ResponseCodeEnum} to the transaction as determined during pre-handle. Will always be set.
 * @param txInfo Information about the transaction that is being handled. If the transaction was not parseable, then
 *               this will be null, and an appropriate error status will be set.
 * @param requiredKeys The set of cryptographic keys that are required to be present.
 * @param optionalKeys The set of cryptographic keys that are optional to be present.
 * @param hollowAccounts The set of required hollow accounts to be finalized
 * @param verificationResults A map of {@link Future<SignatureVerificationFuture>} yielding the
 *                            {@link SignatureVerificationFuture} for a given cryptographic key. Ony cryptographic keys
 *                            are used as the key of this map.
 * @param innerResults {@link PreHandleResult}s of the inner transactions if this is an atomic batch transaction.
 * @param configVersion The version of the configuration that was used during pre-handle
 */
public record PreHandleResult(
        @Nullable AccountID payer,
        @Nullable Key payerKey,
        @NonNull Status status,
        @NonNull ResponseCodeEnum responseCode,
        @Nullable TransactionInfo txInfo,
        @Nullable Set<Key> requiredKeys,
        @Nullable Set<Key> optionalKeys,
        @Nullable Set<Account> hollowAccounts,
        @Nullable Map<Key, SignatureVerificationFuture> verificationResults,
        @Nullable List<PreHandleResult> innerResults,
        long configVersion) {

    /**
     * Returns whether this result's verification results are valid for the given context. This is <b>only</b>
     * true if all keys linked to the transaction are exactly the same as those determined to be necessary
     * in the given context.
     *
     * <p>Any change at all in a linked key means we must re-compute the verification results.
     *
     * @param context the context that might be able to reuse our verification results
     * @return whether our verification results are reusable in the given context
     */
    public boolean hasReusableVerificationResultsFor(@NonNull final PreHandleContext context) {
        return getPayerKey().equals(context.payerKey())
                && getRequiredKeys().equals(context.requiredNonPayerKeys())
                && getOptionalKeys().equals(context.optionalNonPayerKeys())
                && getHollowAccounts().equals(context.requiredHollowAccounts());
    }

    /**
     * Returns whether this result represents a node due diligence failure.
     */
    public HederaRecordCache.DueDiligenceFailure dueDiligenceFailure() {
        return status == NODE_DUE_DILIGENCE_FAILURE
                ? HederaRecordCache.DueDiligenceFailure.YES
                : HederaRecordCache.DueDiligenceFailure.NO;
    }

    /**
     * Returns the transaction info for this result, or throws an exception if it is not available.
     */
    public @NonNull TransactionInfo txnInfoOrThrow() {
        return requireNonNull(txInfo);
    }

    /**
     * Returns the key verifications for this result; or an empty map if none could be computed.
     *
     * @return the key verifications for this result; or an empty map if none could be computed.
     */
    public @NonNull Map<Key, SignatureVerificationFuture> getVerificationResults() {
        return verificationResults == null ? Collections.emptyMap() : verificationResults;
    }

    public @NonNull Set<Key> getRequiredKeys() {
        return requiredKeys == null ? Collections.emptySet() : requiredKeys;
    }

    public @NonNull Set<Key> getOptionalKeys() {
        return optionalKeys == null ? Collections.emptySet() : optionalKeys;
    }

    public @NonNull Key getPayerKey() {
        return payerKey == null ? IMMUTABILITY_SENTINEL_KEY : payerKey;
    }

    public Set<Account> getHollowAccounts() {
        return hollowAccounts == null ? Collections.emptySet() : hollowAccounts;
    }

    /**
     * An enumeration of all possible types of pre-handle results.
     */
    public enum Status {
        /** When the pre-handle fails for completely unknown reasons. This indicates a bug in our software. */
        UNKNOWN_FAILURE,
        /** When the pre-handle fails due to node due diligence failures. */
        NODE_DUE_DILIGENCE_FAILURE,
        /**
         * When the pre-handle fails because the payer was either unwilling (or unable) to pay the service fee.
         */
        PAYER_UNWILLING_OR_UNABLE_TO_PAY_SERVICE_FEE,
        /**
         * When the pre-handle fails for any case of the combination of state and transaction being invalid,
         * such a missing account or invalid transaction body data.
         */
        PRE_HANDLE_FAILURE,
        /**
         * The pre-handle ended successfully. Since signature verification is asynchronous, it may be that the
         * transaction is still invalid, but we have done all we can to verify it at this point. The handle code
         * will need to check the signature verification results to see what the true status is.
         */
        SO_FAR_SO_GOOD
    }

    private static final long UNKNOWN_VERSION = -1L;

    /** Create a new instance. */
    public PreHandleResult {
        requireNonNull(status);
        requireNonNull(responseCode);
    }

    /**
     * Creates a new {@link PreHandleResult} in the event of a random failure that should not be automatically
     * charged to the node. Instead, during the handle phase, we will try again and charge the node if it fails again.
     * The {@link #responseCode()} will be set to {@link ResponseCodeEnum#UNKNOWN} and {@link #status()} will be set
     * to {@link Status#UNKNOWN_FAILURE}.
     *
     * @return A new {@link PreHandleResult} with the given parameters.
     */
    @NonNull
    public static PreHandleResult unknownFailure() {
        return new PreHandleResult(
                null, null, Status.UNKNOWN_FAILURE, UNKNOWN, null, null, null, null, null, null, UNKNOWN_VERSION);
    }

    /**
     * Creates a new {@link PreHandleResult} in the event of node due diligence failure. The node itself will be charged
     * for the transaction. If the {@link TransactionInfo} is not available because the failure happened while parsing
     * the bytes, then it may be omitted as {@code null}. The {@link #status()} will be set to
     * {@link Status#NODE_DUE_DILIGENCE_FAILURE}.
     *
     * @param node The node that is responsible for paying for this due diligence failure.
     * @param responseCode The response code of the failure.
     * @param txInfo The transaction info, if available.
     * @param configVersion The version of the configuration that was used to compute the result
     * @return A new {@link PreHandleResult} with the given parameters.
     */
    @NonNull
    public static PreHandleResult nodeDueDiligenceFailure(
            @NonNull final AccountID node,
            @NonNull final ResponseCodeEnum responseCode,
            @Nullable final TransactionInfo txInfo,
            final long configVersion) {
        return new PreHandleResult(
                node,
                null,
                Status.NODE_DUE_DILIGENCE_FAILURE,
                responseCode,
                txInfo,
                null,
                null,
                null,
                null,
                null,
                configVersion);
    }

    /**
     * Creates a new {@link PreHandleResult} in the event of a failure that should be charged to the payer.
     *
     * @param payer The account that will pay for this transaction.
     * @param responseCode The responseCode code of the failure.
     * @param txInfo The transaction info
     * @param verificationResults A map of {@link Future<SignatureVerificationFuture>} yielding the
     * {@link SignatureVerificationFuture} for a given cryptographic key. Ony cryptographic keys are used as the key of
     * this map.
     * @return A new {@link PreHandleResult} with the given parameters.
     */
    @NonNull
    public static PreHandleResult preHandleFailure(
            @NonNull final AccountID payer,
            @Nullable final Key payerKey,
            @NonNull final ResponseCodeEnum responseCode,
            @NonNull final TransactionInfo txInfo,
            @Nullable Set<Key> requiredKeys,
            @Nullable Set<Key> optionalKeys,
            @Nullable Set<Account> hollowAccounts,
            @Nullable Map<Key, SignatureVerificationFuture> verificationResults) {
        return new PreHandleResult(
                payer,
                payerKey,
                Status.PRE_HANDLE_FAILURE,
                responseCode,
                txInfo,
                requiredKeys,
                optionalKeys,
                hollowAccounts,
                verificationResults,
                null,
                UNKNOWN_VERSION);
    }

    /**
     * Creates a new {@link PreHandleResult} when encountering a {@link com.hedera.hapi.platform.event.StateSignatureTransaction}.
     */
    @NonNull
    public static PreHandleResult stateSignatureTransactionEncountered(@NonNull final TransactionInfo txInfo) {
        return new PreHandleResult(
                null, null, Status.SO_FAR_SO_GOOD, UNKNOWN, txInfo, null, null, null, null, null, UNKNOWN_VERSION);
    }
}
