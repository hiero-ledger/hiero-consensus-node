// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody.DataOneOfType;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A package-private utility class for Schedule Handlers.
 */
public final class HandlerUtility {
    private HandlerUtility() {}

    /**
     * Return child as ordinary transaction body.
     *
     * @param scheduleInState the schedule in state to convert to transaction body
     * @return the transaction body
     */
    @NonNull
    public static TransactionBody childAsOrdinary(@NonNull final Schedule scheduleInState) {
        final var scheduledTransactionId = transactionIdForScheduled(scheduleInState);
        final var ordinary = TransactionBody.newBuilder();
        final var scheduledBody = scheduleInState.scheduledTransaction();
        if (scheduledBody != null) {
            ordinary.transactionFee(scheduledBody.transactionFee())
                    .memo(scheduledBody.memo())
                    .maxCustomFees(scheduledBody.maxCustomFees())
                    .transactionID(scheduledTransactionId);
            switch (scheduledBody.data().kind()) {
                case CONSENSUS_CREATE_TOPIC ->
                    ordinary.consensusCreateTopic(scheduledBody.consensusCreateTopicOrThrow());
                case CONSENSUS_UPDATE_TOPIC ->
                    ordinary.consensusUpdateTopic(scheduledBody.consensusUpdateTopicOrThrow());
                case CONSENSUS_DELETE_TOPIC ->
                    ordinary.consensusDeleteTopic(scheduledBody.consensusDeleteTopicOrThrow());
                case CONSENSUS_SUBMIT_MESSAGE ->
                    ordinary.consensusSubmitMessage(scheduledBody.consensusSubmitMessageOrThrow());
                case CRYPTO_CREATE_ACCOUNT -> ordinary.cryptoCreateAccount(scheduledBody.cryptoCreateAccountOrThrow());
                case CRYPTO_UPDATE_ACCOUNT -> ordinary.cryptoUpdateAccount(scheduledBody.cryptoUpdateAccountOrThrow());
                case CRYPTO_TRANSFER -> ordinary.cryptoTransfer(scheduledBody.cryptoTransferOrThrow());
                case CRYPTO_DELETE -> ordinary.cryptoDelete(scheduledBody.cryptoDeleteOrThrow());
                case FILE_CREATE -> ordinary.fileCreate(scheduledBody.fileCreateOrThrow());
                case FILE_APPEND -> ordinary.fileAppend(scheduledBody.fileAppendOrThrow());
                case FILE_UPDATE -> ordinary.fileUpdate(scheduledBody.fileUpdateOrThrow());
                case FILE_DELETE -> ordinary.fileDelete(scheduledBody.fileDeleteOrThrow());
                case CONTRACT_CREATE_INSTANCE ->
                    ordinary.contractCreateInstance(scheduledBody.contractCreateInstanceOrThrow());
                case CONTRACT_UPDATE_INSTANCE ->
                    ordinary.contractUpdateInstance(scheduledBody.contractUpdateInstanceOrThrow());
                case CONTRACT_CALL -> ordinary.contractCall(scheduledBody.contractCallOrThrow());
                case CONTRACT_DELETE_INSTANCE ->
                    ordinary.contractDeleteInstance(scheduledBody.contractDeleteInstanceOrThrow());
                case SYSTEM_DELETE -> ordinary.systemDelete(scheduledBody.systemDeleteOrThrow());
                case SYSTEM_UNDELETE -> ordinary.systemUndelete(scheduledBody.systemUndeleteOrThrow());
                case FREEZE -> ordinary.freeze(scheduledBody.freezeOrThrow());
                case TOKEN_CREATION -> ordinary.tokenCreation(scheduledBody.tokenCreationOrThrow());
                case TOKEN_FREEZE -> ordinary.tokenFreeze(scheduledBody.tokenFreezeOrThrow());
                case TOKEN_UNFREEZE -> ordinary.tokenUnfreeze(scheduledBody.tokenUnfreezeOrThrow());
                case TOKEN_GRANT_KYC -> ordinary.tokenGrantKyc(scheduledBody.tokenGrantKycOrThrow());
                case TOKEN_REVOKE_KYC -> ordinary.tokenRevokeKyc(scheduledBody.tokenRevokeKycOrThrow());
                case TOKEN_DELETION -> ordinary.tokenDeletion(scheduledBody.tokenDeletionOrThrow());
                case TOKEN_UPDATE -> ordinary.tokenUpdate(scheduledBody.tokenUpdateOrThrow());
                case TOKEN_MINT -> ordinary.tokenMint(scheduledBody.tokenMintOrThrow());
                case TOKEN_BURN -> ordinary.tokenBurn(scheduledBody.tokenBurnOrThrow());
                case TOKEN_WIPE -> ordinary.tokenWipe(scheduledBody.tokenWipeOrThrow());
                case TOKEN_ASSOCIATE -> ordinary.tokenAssociate(scheduledBody.tokenAssociateOrThrow());
                case TOKEN_DISSOCIATE -> ordinary.tokenDissociate(scheduledBody.tokenDissociateOrThrow());
                case SCHEDULE_DELETE -> ordinary.scheduleDelete(scheduledBody.scheduleDeleteOrThrow());
                case TOKEN_PAUSE -> ordinary.tokenPause(scheduledBody.tokenPauseOrThrow());
                case TOKEN_UNPAUSE -> ordinary.tokenUnpause(scheduledBody.tokenUnpauseOrThrow());
                case CRYPTO_APPROVE_ALLOWANCE ->
                    ordinary.cryptoApproveAllowance(scheduledBody.cryptoApproveAllowanceOrThrow());
                case CRYPTO_DELETE_ALLOWANCE ->
                    ordinary.cryptoDeleteAllowance(scheduledBody.cryptoDeleteAllowanceOrThrow());
                case TOKEN_FEE_SCHEDULE_UPDATE ->
                    ordinary.tokenFeeScheduleUpdate(scheduledBody.tokenFeeScheduleUpdateOrThrow());
                case UTIL_PRNG -> ordinary.utilPrng(scheduledBody.utilPrngOrThrow());
                case TOKEN_REJECT -> ordinary.tokenReject(scheduledBody.tokenRejectOrThrow());
                case NODE_CREATE -> ordinary.nodeCreate(scheduledBody.nodeCreateOrThrow());
                case NODE_UPDATE -> ordinary.nodeUpdate(scheduledBody.nodeUpdateOrThrow());
                case NODE_DELETE -> ordinary.nodeDelete(scheduledBody.nodeDeleteOrThrow());
                case UNSET -> throw new HandleException(ResponseCodeEnum.INVALID_TRANSACTION);
            }
        }
        return ordinary.build();
    }

    /**
     * Given a Transaction of one type, return the corresponding HederaFunctionality.
     * @param transactionType the transaction type
     * @return the hedera functionality
     */
    public static HederaFunctionality functionalityForType(@NonNull final DataOneOfType transactionType) {
        requireNonNull(transactionType);
        return switch (transactionType) {
            case CONSENSUS_CREATE_TOPIC -> HederaFunctionality.CONSENSUS_CREATE_TOPIC;
            case CONSENSUS_UPDATE_TOPIC -> HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
            case CONSENSUS_DELETE_TOPIC -> HederaFunctionality.CONSENSUS_DELETE_TOPIC;
            case CONSENSUS_SUBMIT_MESSAGE -> HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
            case CRYPTO_CREATE_ACCOUNT -> HederaFunctionality.CRYPTO_CREATE;
            case CRYPTO_UPDATE_ACCOUNT -> HederaFunctionality.CRYPTO_UPDATE;
            case CRYPTO_TRANSFER -> HederaFunctionality.CRYPTO_TRANSFER;
            case CRYPTO_DELETE -> HederaFunctionality.CRYPTO_DELETE;
            case FILE_CREATE -> HederaFunctionality.FILE_CREATE;
            case FILE_APPEND -> HederaFunctionality.FILE_APPEND;
            case FILE_UPDATE -> HederaFunctionality.FILE_UPDATE;
            case FILE_DELETE -> HederaFunctionality.FILE_DELETE;
            case SYSTEM_DELETE -> HederaFunctionality.SYSTEM_DELETE;
            case SYSTEM_UNDELETE -> HederaFunctionality.SYSTEM_UNDELETE;
            case CONTRACT_CREATE_INSTANCE -> HederaFunctionality.CONTRACT_CREATE;
            case CONTRACT_UPDATE_INSTANCE -> HederaFunctionality.CONTRACT_UPDATE;
            case CONTRACT_CALL -> HederaFunctionality.CONTRACT_CALL;
            case CONTRACT_DELETE_INSTANCE -> HederaFunctionality.CONTRACT_DELETE;
            case FREEZE -> HederaFunctionality.FREEZE;
            case TOKEN_CREATION -> HederaFunctionality.TOKEN_CREATE;
            case TOKEN_FREEZE -> HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
            case TOKEN_UNFREEZE -> HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
            case TOKEN_GRANT_KYC -> HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
            case TOKEN_REVOKE_KYC -> HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
            case TOKEN_DELETION -> HederaFunctionality.TOKEN_DELETE;
            case TOKEN_UPDATE -> HederaFunctionality.TOKEN_UPDATE;
            case TOKEN_MINT -> HederaFunctionality.TOKEN_MINT;
            case TOKEN_BURN -> HederaFunctionality.TOKEN_BURN;
            case TOKEN_WIPE -> HederaFunctionality.TOKEN_ACCOUNT_WIPE;
            case TOKEN_ASSOCIATE -> HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
            case TOKEN_DISSOCIATE -> HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
            case SCHEDULE_DELETE -> HederaFunctionality.SCHEDULE_DELETE;
            case TOKEN_PAUSE -> HederaFunctionality.TOKEN_PAUSE;
            case TOKEN_UNPAUSE -> HederaFunctionality.TOKEN_UNPAUSE;
            case CRYPTO_APPROVE_ALLOWANCE -> HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
            case CRYPTO_DELETE_ALLOWANCE -> HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
            case TOKEN_FEE_SCHEDULE_UPDATE -> HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
            case UTIL_PRNG -> HederaFunctionality.UTIL_PRNG;
            case TOKEN_UPDATE_NFTS -> HederaFunctionality.TOKEN_UPDATE_NFTS;
            case TOKEN_REJECT -> HederaFunctionality.TOKEN_REJECT;
            case NODE_CREATE -> HederaFunctionality.NODE_CREATE;
            case NODE_UPDATE -> HederaFunctionality.NODE_UPDATE;
            case NODE_DELETE -> HederaFunctionality.NODE_DELETE;
            case TOKEN_CANCEL_AIRDROP -> HederaFunctionality.TOKEN_CANCEL_AIRDROP;
            case TOKEN_CLAIM_AIRDROP -> HederaFunctionality.TOKEN_CLAIM_AIRDROP;
            case TOKEN_AIRDROP -> HederaFunctionality.TOKEN_AIRDROP;
            case UNSET -> HederaFunctionality.NONE;
        };
    }

    /**
     * Create a new Schedule, but without an ID or signatories.
     * This method is used to create a schedule object for processing during a ScheduleCreate, but without the
     * schedule ID, as we still need to complete validation and other processing.  Once all processing is complete,
     * a new ID is allocated and signatories are added immediately prior to storing the new object in state.
     * @param body The transaction body of the current Schedule Create transaction.  We assume that
     *     the transaction is a ScheduleCreate, but require the less specific object so that we have access to
     *     the transaction ID via {@link TransactionBody#transactionID()} from the TransactionBody stored in
     *     the {@link Schedule#originalCreateTransaction()} attribute of the Schedule.
     * @param consensusNow The current consensus time for the network.
     * @param defaultLifetime The maximum number of seconds a schedule is permitted to exist on the ledger
     *     before it expires.
     * @return a newly created Schedule with a null schedule ID
     * @throws HandleException if the
     */
    @NonNull
    static Schedule createProvisionalSchedule(
            @NonNull final TransactionBody body,
            @NonNull final Instant consensusNow,
            final long defaultLifetime,
            final boolean longTermEnabled) {
        final var txnId = body.transactionIDOrThrow();
        final var op = body.scheduleCreateOrThrow();
        final var payerId = txnId.accountIDOrThrow();
        final long expiry = calculateExpiration(op.expirationTime(), consensusNow, defaultLifetime, longTermEnabled);
        final var builder = Schedule.newBuilder();
        if (longTermEnabled) {
            builder.waitForExpiry(op.waitForExpiry());
        }
        return builder.scheduleId((ScheduleID) null)
                .deleted(false)
                .executed(false)
                .adminKey(op.adminKey())
                .schedulerAccountId(payerId)
                .payerAccountId(op.payerAccountIDOrElse(payerId))
                .schedulerAccountId(payerId)
                .scheduleValidStart(txnId.transactionValidStart())
                .calculatedExpirationSecond(expiry)
                .providedExpirationSecond(
                        op.expirationTimeOrElse(Timestamp.DEFAULT).seconds())
                .originalCreateTransaction(body)
                .memo(op.memo())
                .scheduledTransaction(op.scheduledTransactionBody())
                .build();
    }

    /**
     * Builds the transaction id for a scheduled transaction from its schedule.
     * @param schedule the schedule
     * @return its transaction id
     */
    @NonNull
    static TransactionID transactionIdForScheduled(@NonNull final Schedule schedule) {
        return scheduledTxnIdFrom(schedule.originalCreateTransactionOrThrow().transactionIDOrThrow());
    }

    /**
     * Given the scheduling transaction ID, return the scheduled transaction ID.
     * @param schedulingTxnId the scheduling transaction ID
     * @return the scheduled transaction ID
     */
    public static TransactionID scheduledTxnIdFrom(@NonNull final TransactionID schedulingTxnId) {
        requireNonNull(schedulingTxnId);
        return schedulingTxnId.scheduled()
                ? schedulingTxnId
                        .copyBuilder()
                        .nonce(schedulingTxnId.nonce() + 1)
                        .build()
                : schedulingTxnId.copyBuilder().scheduled(true).build();
    }

    private static long calculateExpiration(
            @Nullable final Timestamp givenExpiration,
            @NonNull final Instant consensusNow,
            final long defaultLifetime,
            final boolean longTermEnabled) {
        if (givenExpiration != null && longTermEnabled) {
            return givenExpiration.seconds();
        } else {
            return consensusNow.plusSeconds(defaultLifetime).getEpochSecond();
        }
    }
}
