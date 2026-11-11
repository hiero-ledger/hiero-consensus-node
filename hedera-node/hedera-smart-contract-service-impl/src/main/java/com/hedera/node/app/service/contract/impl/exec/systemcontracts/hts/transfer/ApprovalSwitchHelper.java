// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.node.app.spi.key.RequirementVerifier;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Helper class for switching unauthorized debits to approvals in a synthetic
 * {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}.
 */
public class ApprovalSwitchHelper {
    /**
     * Singleton instance.
     */
    public static final ApprovalSwitchHelper APPROVAL_SWITCH_HELPER = new ApprovalSwitchHelper();

    private ApprovalSwitchHelper() {
        // Singleton
    }

    /**
     * Given a synthetic {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}, returns a new
     * {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody} that is identical except any debits
     * whose linked signing keys do not have active signatures are switched to approvals.
     *
     * @param original the synthetic {@link CryptoTransferTransactionBody} to switch
     * @param signatureTest the verifier for each debit account's authority
     * @param nativeOperations the {@link HederaNativeOperations} that provides account key access
     * @return the new {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody}
     */
    public CryptoTransferTransactionBody switchToApprovalsAsNeededIn(
            @NonNull final CryptoTransferTransactionBody original,
            @NonNull final RequirementVerifier signatureTest,
            @NonNull final HederaNativeOperations nativeOperations) {
        requireNonNull(original);
        requireNonNull(signatureTest);
        requireNonNull(nativeOperations);
        final var revision = CryptoTransferTransactionBody.newBuilder();
        if (original.hasTransfers()) {
            final var originalAdjusts = original.transfersOrThrow().accountAmounts();
            revision.transfers(TransferList.newBuilder()
                    .accountAmounts(revisedAdjusts(originalAdjusts, signatureTest, nativeOperations))
                    .build());
        }
        final var originalTokenTransfers = original.tokenTransfers();
        final TokenTransferList[] revisedTokenTransfers = new TokenTransferList[originalTokenTransfers.size()];
        for (int i = 0, n = originalTokenTransfers.size(); i < n; i++) {
            final var originalTokenTransfer = originalTokenTransfers.get(i);
            final var revisedTokenTransfer = TokenTransferList.newBuilder().token(originalTokenTransfer.token());
            if (originalTokenTransfer.transfers().isEmpty()) {
                revisedTokenTransfer.nftTransfers(
                        revisedOwnershipChanges(originalTokenTransfer.nftTransfers(), signatureTest, nativeOperations));
            } else {
                revisedTokenTransfer.transfers(
                        revisedAdjusts(originalTokenTransfer.transfers(), signatureTest, nativeOperations));
            }
            revisedTokenTransfers[i] = revisedTokenTransfer.build();
        }
        revision.tokenTransfers(revisedTokenTransfers);
        return revision.build();
    }

    private AccountAmount[] revisedAdjusts(
            @NonNull final List<AccountAmount> original,
            @NonNull final RequirementVerifier signatureTest,
            @NonNull final HederaNativeOperations nativeOperations) {
        final AccountAmount[] revision = new AccountAmount[original.size()];
        for (int i = 0, n = original.size(); i < n; i++) {
            revision[i] = revisedAdjust(original.get(i), signatureTest, nativeOperations);
        }
        return revision;
    }

    private AccountAmount revisedAdjust(
            @NonNull final AccountAmount original,
            @NonNull final RequirementVerifier signatureTest,
            @NonNull final HederaNativeOperations nativeOperations) {
        if (original.amount() < 0) {
            final var debitedAccountId = original.accountIDOrThrow();
            final var account = nativeOperations.getAccount(debitedAccountId);
            if (account != null
                    && !signatureTest.test(
                            KeyRequirement.forAccount(account.accountIdOrThrow(), account.keyOrThrow()))) {
                return original.copyBuilder().isApproval(true).build();
            }
        }
        return original;
    }

    private NftTransfer[] revisedOwnershipChanges(
            @NonNull final List<NftTransfer> original,
            @NonNull final RequirementVerifier signatureTest,
            @NonNull final HederaNativeOperations nativeOperations) {
        final NftTransfer[] revision = new NftTransfer[original.size()];
        for (int i = 0, n = original.size(); i < n; i++) {
            revision[i] = revisedNftTransfer(original.get(i), signatureTest, nativeOperations);
        }
        return revision;
    }

    private NftTransfer revisedNftTransfer(
            @NonNull final NftTransfer original,
            @NonNull final RequirementVerifier signatureTest,
            @NonNull final HederaNativeOperations nativeOperations) {
        final var transferAccountId = original.senderAccountIDOrThrow();
        final var account = nativeOperations.getAccount(transferAccountId);
        if (account != null
                && !signatureTest.test(KeyRequirement.forAccount(account.accountIdOrThrow(), account.keyOrThrow()))) {
            return original.copyBuilder().isApproval(true).build();
        }
        return original;
    }
}
