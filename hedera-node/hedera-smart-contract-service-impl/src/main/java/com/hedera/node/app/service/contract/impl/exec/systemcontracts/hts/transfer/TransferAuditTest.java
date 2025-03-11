// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Defines a type that can test each hbar, token, and NFT transfer in a
 * {@link com.hedera.hapi.node.token.CryptoTransferTransactionBody} for some condition.
 */
public interface TransferAuditTest {
    /**
     * Returns {@code true} if the given hbar or fungible token {@link AccountAmount}
     * adjustment raises the audit flag.
     *
     * @param adjust the {@link AccountAmount} to test
     * @return whether the audit flag is raised
     */
    boolean flagsAdjustment(@NonNull AccountAmount adjust);

    /**
     * Returns {@code true} if the given {@link NftTransfer} raises the audit flag.
     *
     * @param nftTransfer the {@link NftTransfer} to test
     * @return whether the audit flag is raised
     */
    boolean flagsNftTransfer(@NonNull NftTransfer nftTransfer);

    /**
     * Returns {@code true} if the given {@link CryptoTransferTransactionBody} raises the audit flag.
     *
     * @param op the {@link CryptoTransferTransactionBody} to test
     * @param auditTest the {@link TransferAuditTest} to use
     * @return whether the audit flag is raised
     */
    static boolean isAuditFlagRaised(@NonNull CryptoTransferTransactionBody op, @NonNull TransferAuditTest auditTest) {
        final var hbarAdjusts = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();
        for (final var adjust : hbarAdjusts) {
            if (auditTest.flagsAdjustment(adjust)) {
                return true;
            }
        }
        final var tokenTransferLists = op.tokenTransfers();
        for (final var tokenTransferList : tokenTransferLists) {
            final var tokenAdjusts = tokenTransferList.transfers();
            for (final var adjust : tokenAdjusts) {
                if (auditTest.flagsAdjustment(adjust)) {
                    return true;
                }
            }
            final var nftTransfers = tokenTransferList.nftTransfers();
            for (final var nftTransfer : nftTransfers) {
                if (auditTest.flagsNftTransfer(nftTransfer)) {
                    return true;
                }
            }
        }
        return false;
    }
}
