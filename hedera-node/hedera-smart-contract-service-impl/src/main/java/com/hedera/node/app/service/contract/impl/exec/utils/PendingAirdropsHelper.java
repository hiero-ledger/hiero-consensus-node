// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asTokenId;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;

public final class PendingAirdropsHelper {

    private PendingAirdropsHelper() {
        // Utility class
    }

    // Tuple indexes for PENDING AIRDROP struct type
    private static final int SENDER = 0;
    private static final int RECEIVER = 1;
    private static final int TOKEN = 2;
    private static final int SERIAL = 3;

    public static @NonNull List<PendingAirdropId> decodePendingAirdrops(
            @NonNull final HtsCallAttempt attempt, @NonNull final Tuple[] transferList) {
        return Arrays.stream(transferList)
                .map(transfer -> {
                    final var senderAddress = (Address) transfer.get(SENDER);
                    final var receiverAddress = (Address) transfer.get(RECEIVER);
                    final var tokenAddress = (Address) transfer.get(TOKEN);
                    final var serial = (long) transfer.get(SERIAL);

                    final var senderId = attempt.addressIdConverter().convert(senderAddress);
                    final var receiverId = attempt.addressIdConverter().convert(receiverAddress);
                    final var tokenId = asTokenId(attempt.nativeOperations().entityIdFactory(), tokenAddress);

                    final var token = attempt.enhancement().nativeOperations().getToken(tokenId);
                    validateTrue(token != null, INVALID_TOKEN_ID);
                    if (token.tokenType().equals(TokenType.FUNGIBLE_COMMON)) {
                        return pendingFTAirdrop(senderId, receiverId, tokenId);
                    } else {
                        return pendingNFTAirdrop(senderId, receiverId, tokenId, serial);
                    }
                })
                .toList();
    }

    public static PendingAirdropId pendingFTAirdrop(
            @NonNull final AccountID senderId, @NonNull final AccountID receiverId, @NonNull final TokenID tokenId) {
        return PendingAirdropId.newBuilder()
                .senderId(senderId)
                .receiverId(receiverId)
                .fungibleTokenType(tokenId)
                .build();
    }

    public static PendingAirdropId pendingNFTAirdrop(
            @NonNull final AccountID senderId,
            @NonNull final AccountID receiverId,
            @NonNull final TokenID tokenId,
            final long serial) {
        return PendingAirdropId.newBuilder()
                .senderId(senderId)
                .receiverId(receiverId)
                .nonFungibleToken(NftID.newBuilder().tokenId(tokenId).serialNumber(serial))
                .build();
    }
}
