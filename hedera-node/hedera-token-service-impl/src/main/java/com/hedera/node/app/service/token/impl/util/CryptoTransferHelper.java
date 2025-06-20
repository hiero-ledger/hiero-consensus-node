// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.util;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Utility class that provides static methods to facilitate the creation of token transfers for both fungible and
 * non-fungible tokens (NFTs).
 */
public class CryptoTransferHelper {

    private CryptoTransferHelper() {
        throw new UnsupportedOperationException("Utility class only");
    }

    /**
     * Creates a {@link TokenTransferList} for a fungible token transfer.
     *
     * @param tokenId the ID of the token to be transferred
     * @param fromAccount the account ID from which tokens are debited
     * @param amount the amount of tokens to be transferred
     * @param toAccount the account ID to which tokens are credited
     * @return TokenTransferList representing the transfer of fungible tokens
     */
    public static TokenTransferList createFungibleTransfer(
            final TokenID tokenId, final AccountID fromAccount, final long amount, final AccountID toAccount) {
        return TokenTransferList.newBuilder()
                .token(tokenId)
                .transfers(
                        AccountAmount.newBuilder()
                                .accountID(fromAccount)
                                .amount(-amount)
                                .build(),
                        AccountAmount.newBuilder()
                                .accountID(toAccount)
                                .amount(amount)
                                .build())
                .build();
    }

    /**
     * Creates a {@link AccountAmount} for a TokenTransferList.
     *
     * @param account the account
     * @param amount the amount
     * @param isApproval is approval
     * @return AccountAmount containing the NFT transfer
     */
    public static AccountAmount createAccountAmount(final AccountID account, final long amount, boolean isApproval) {
        return AccountAmount.newBuilder()
                .accountID(account)
                .amount(amount)
                .isApproval(isApproval)
                .build();
    }

    /**
     * Creates a {@link TokenTransferList} for a non-fungible token (NFT) transfer.
     *
     * @param tokenId the TokenID of the NFT to be transferred
     * @param nftTransfer the NftTransfer object
     * @return TokenTransferList containing the NFT transfer
     */
    public static TokenTransferList createNftTransfer(final TokenID tokenId, final NftTransfer nftTransfer) {
        return TokenTransferList.newBuilder()
                .token(tokenId)
                .nftTransfers(nftTransfer)
                .build();
    }

    /**
     * Creates a {@link TokenTransferList} for a non-fungible token (NFT) transfer.
     *
     * @param tokenId the TokenID of the NFT to be transferred
     * @param nftTransfers the list of NftTransfer objects
     * @return TokenTransferList containing the NFT transfer
     */
    public static TokenTransferList createNftTransfer(final TokenID tokenId, final List<NftTransfer> nftTransfers) {
        return TokenTransferList.newBuilder()
                .token(tokenId)
                .nftTransfers(nftTransfers)
                .build();
    }

    /**
     * Constructs a {@link NftTransfer} object for transferring a non-fungible token.
     *
     * @param from the sender's account ID
     * @param to the receiver's account ID
     * @param serialNo the serial number of the NFT
     * @return NftTransfer object detailing the sender, receiver, and NFT serial number
     */
    public static NftTransfer nftTransfer(
            @NonNull final AccountID from, @NonNull final AccountID to, final long serialNo) {
        return NftTransfer.newBuilder()
                .serialNumber(serialNo)
                .senderAccountID(from)
                .receiverAccountID(to)
                .build();
    }
}
