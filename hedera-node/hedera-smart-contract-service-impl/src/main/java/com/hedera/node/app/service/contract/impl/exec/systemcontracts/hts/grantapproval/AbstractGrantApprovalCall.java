// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implements the grant approval {@code approve()} call of the HTS system contract.
 */
public abstract class AbstractGrantApprovalCall extends AbstractCall {
    protected final VerificationStrategy verificationStrategy;
    protected final AccountID senderId;
    protected final TokenID tokenId;
    protected final AccountID spenderId;
    protected final TokenType tokenType;
    protected final long amount;

    // too many parameters
    @SuppressWarnings("java:S107")
    protected AbstractGrantApprovalCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final Enhancement enhancement,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID senderId,
            @NonNull final TokenID tokenId,
            @NonNull final AccountID spenderId,
            final long amount,
            @NonNull final TokenType tokenType,
            final boolean isViewCall) {
        super(gasCalculator, enhancement, isViewCall);
        this.verificationStrategy = verificationStrategy;
        this.senderId = senderId;
        this.tokenId = tokenId;
        this.spenderId = spenderId;
        this.amount = amount;
        this.tokenType = tokenType;
    }

    protected ContractCallStreamBuilder withMonoStandard(@NonNull final ContractCallStreamBuilder recordBuilder) {
        if (recordBuilder.status() == DELEGATING_SPENDER_DOES_NOT_HAVE_APPROVE_FOR_ALL
                || recordBuilder.status() == INVALID_SIGNATURE) {
            recordBuilder.status(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
        }
        return recordBuilder;
    }

    protected TransactionBody synthApprovalBody() {
        if (tokenType == TokenType.NON_FUNGIBLE_UNIQUE) {
            var ownerId = getMaybeOwnerId();

            if (ownerId != null && !isNftApprovalRevocation()) {
                if (!ownerId.equals(senderId)) {
                    return buildCryptoApproveAllowance(approveDelegate(ownerId, senderId));
                }
            }

            return isNftApprovalRevocation()
                    ? buildCryptoDeleteAllowance(remove(ownerId))
                    : buildCryptoApproveAllowance(approve(ownerId));
        } else {
            return buildCryptoApproveAllowance(approve(senderId));
        }
    }

    private CryptoDeleteAllowanceTransactionBody remove(AccountID ownerId) {
        return CryptoDeleteAllowanceTransactionBody.newBuilder()
                .nftAllowances(NftRemoveAllowance.newBuilder()
                        .tokenId(tokenId)
                        .owner(ownerId)
                        .serialNumbers(amount)
                        .build())
                .build();
    }

    private CryptoApproveAllowanceTransactionBody approveDelegate(AccountID ownerId, AccountID delegateSpenderId) {
        return CryptoApproveAllowanceTransactionBody.newBuilder()
                .nftAllowances(NftAllowance.newBuilder()
                        .tokenId(tokenId)
                        .spender(spenderId)
                        .delegatingSpender(delegateSpenderId)
                        .owner(ownerId)
                        .serialNumbers(amount)
                        .build())
                .build();
    }

    private CryptoApproveAllowanceTransactionBody approve(AccountID ownerId) {
        return tokenType.equals(TokenType.FUNGIBLE_COMMON)
                ? CryptoApproveAllowanceTransactionBody.newBuilder()
                        .tokenAllowances(TokenAllowance.newBuilder()
                                .tokenId(tokenId)
                                .spender(spenderId)
                                .owner(ownerId)
                                .amount(amount)
                                .build())
                        .build()
                : CryptoApproveAllowanceTransactionBody.newBuilder()
                        .nftAllowances(NftAllowance.newBuilder()
                                .tokenId(tokenId)
                                .spender(spenderId)
                                .owner(ownerId)
                                .serialNumbers(amount)
                                .build())
                        .build();
    }

    private TransactionBody buildCryptoDeleteAllowance(CryptoDeleteAllowanceTransactionBody body) {
        return TransactionBody.newBuilder().cryptoDeleteAllowance(body).build();
    }

    private TransactionBody buildCryptoApproveAllowance(CryptoApproveAllowanceTransactionBody body) {
        return TransactionBody.newBuilder().cryptoApproveAllowance(body).build();
    }

    protected @Nullable AccountID getMaybeOwnerId() {
        final var nft = enhancement.nativeOperations().getNft(tokenId, amount);
        if (nft == null) {
            return null;
        }
        if (nft.hasOwnerId()) {
            return nft.ownerId();
        } else {
            final var token = nativeOperations().getToken(tokenId);
            return token == null ? null : token.treasuryAccountIdOrThrow();
        }
    }

    private boolean isNftApprovalRevocation() {
        return spenderId.accountNumOrElse(0L) == 0;
    }
}
