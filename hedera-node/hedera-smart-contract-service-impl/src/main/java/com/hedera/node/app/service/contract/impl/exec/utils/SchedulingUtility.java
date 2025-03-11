// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.HandleException;
import edu.umd.cs.findbugs.annotations.NonNull;

public final class SchedulingUtility {
    private SchedulingUtility() {}

    /**
     * Return SchedulableTransactionBody from an ordinary transaction body.
     *
     * @param transactionBody the transaction body from inner dispatched call
     * @return the schedulable transaction body
     */
    @NonNull
    public static SchedulableTransactionBody ordinaryChildAsSchedulable(
            @NonNull final TransactionBody transactionBody) {
        final var schedulableTransactionBody = SchedulableTransactionBody.newBuilder();
        switch (transactionBody.data().kind()) {
            case CRYPTO_CREATE_ACCOUNT -> schedulableTransactionBody.cryptoCreateAccount(
                    transactionBody.cryptoCreateAccountOrThrow());
            case CRYPTO_UPDATE_ACCOUNT -> schedulableTransactionBody.cryptoUpdateAccount(
                    transactionBody.cryptoUpdateAccountOrThrow());
            case CRYPTO_TRANSFER -> schedulableTransactionBody.cryptoTransfer(transactionBody.cryptoTransferOrThrow());
            case CRYPTO_DELETE -> schedulableTransactionBody.cryptoDelete(transactionBody.cryptoDeleteOrThrow());
            case CONTRACT_CALL -> schedulableTransactionBody.contractCall(transactionBody.contractCallOrThrow());
            case FREEZE -> schedulableTransactionBody.freeze(transactionBody.freezeOrThrow());
            case TOKEN_CREATION -> schedulableTransactionBody.tokenCreation(transactionBody.tokenCreationOrThrow());
            case TOKEN_FREEZE -> schedulableTransactionBody.tokenFreeze(transactionBody.tokenFreezeOrThrow());
            case TOKEN_UNFREEZE -> schedulableTransactionBody.tokenUnfreeze(transactionBody.tokenUnfreezeOrThrow());
            case TOKEN_GRANT_KYC -> schedulableTransactionBody.tokenGrantKyc(transactionBody.tokenGrantKycOrThrow());
            case TOKEN_REVOKE_KYC -> schedulableTransactionBody.tokenRevokeKyc(transactionBody.tokenRevokeKycOrThrow());
            case TOKEN_DELETION -> schedulableTransactionBody.tokenDeletion(transactionBody.tokenDeletionOrThrow());
            case TOKEN_UPDATE -> schedulableTransactionBody.tokenUpdate(transactionBody.tokenUpdateOrThrow());
            case TOKEN_MINT -> schedulableTransactionBody.tokenMint(transactionBody.tokenMintOrThrow());
            case TOKEN_BURN -> schedulableTransactionBody.tokenBurn(transactionBody.tokenBurnOrThrow());
            case TOKEN_WIPE -> schedulableTransactionBody.tokenWipe(transactionBody.tokenWipeOrThrow());
            case TOKEN_ASSOCIATE -> schedulableTransactionBody.tokenAssociate(transactionBody.tokenAssociateOrThrow());
            case TOKEN_DISSOCIATE -> schedulableTransactionBody.tokenDissociate(
                    transactionBody.tokenDissociateOrThrow());
            case TOKEN_PAUSE -> schedulableTransactionBody.tokenPause(transactionBody.tokenPauseOrThrow());
            case TOKEN_UNPAUSE -> schedulableTransactionBody.tokenUnpause(transactionBody.tokenUnpauseOrThrow());
            case CRYPTO_APPROVE_ALLOWANCE -> schedulableTransactionBody.cryptoApproveAllowance(
                    transactionBody.cryptoApproveAllowanceOrThrow());
            case CRYPTO_DELETE_ALLOWANCE -> schedulableTransactionBody.cryptoDeleteAllowance(
                    transactionBody.cryptoDeleteAllowanceOrThrow());
            case TOKEN_FEE_SCHEDULE_UPDATE -> schedulableTransactionBody.tokenFeeScheduleUpdate(
                    transactionBody.tokenFeeScheduleUpdateOrThrow());
            case UTIL_PRNG -> schedulableTransactionBody.utilPrng(transactionBody.utilPrngOrThrow());
            case TOKEN_REJECT -> schedulableTransactionBody.tokenReject(transactionBody.tokenRejectOrThrow());
            default -> throw new HandleException(ResponseCodeEnum.INVALID_TRANSACTION);
        }
        return schedulableTransactionBody.build();
    }
}
