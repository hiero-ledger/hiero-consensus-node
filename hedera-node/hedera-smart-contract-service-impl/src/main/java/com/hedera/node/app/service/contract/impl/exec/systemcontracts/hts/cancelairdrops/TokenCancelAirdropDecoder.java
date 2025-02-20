// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hedera.node.app.service.contract.impl.exec.utils.PendingAirdropsHelper.decodePendingAirdrops;
import static com.hedera.node.app.service.contract.impl.exec.utils.PendingAirdropsHelper.pendingFTAirdrop;
import static com.hedera.node.app.service.contract.impl.exec.utils.PendingAirdropsHelper.pendingNFTAirdrop;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.token.TokenCancelAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

public class TokenCancelAirdropDecoder {

    // Tuple indexes
    // Indexes for CANCEL_AIRDROP
    // cancelAirdrops((address,address,address,int64)[])
    private static final int TRANSFER_LIST = 0;

    // Indexes for HRC_CANCEL_AIRDROP_FT and HRC_CANCEL_AIRDROP_NFT
    // cancelAirdropFT(address)
    // cancelAirdropNFT(address,int64)
    private static final int HRC_RECEIVER = 0;
    private static final int HRC_SERIAL = 1;

    @Inject
    public TokenCancelAirdropDecoder() {
        // Dagger2
    }

    public TransactionBody decodeCancelAirdrop(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenCancelAirdropTranslator.CANCEL_AIRDROPS.decodeCall(attempt.inputBytes());
        final var maxPendingAirdropsToCancel =
                attempt.configuration().getConfigData(TokensConfig.class).maxAllowedPendingAirdropsToCancel();
        final var transferList = (Tuple[]) call.get(TRANSFER_LIST);
        validateFalse(transferList.length > maxPendingAirdropsToCancel, PENDING_AIRDROP_ID_LIST_TOO_LONG);

        final var pendingAirdrops = decodePendingAirdrops(attempt, transferList);

        return TransactionBody.newBuilder()
                .tokenCancelAirdrop(
                        TokenCancelAirdropTransactionBody.newBuilder().pendingAirdrops(pendingAirdrops))
                .build();
    }

    public TransactionBody decodeCancelAirdropFT(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_FT.decodeCall(attempt.inputBytes());

        final var senderId = attempt.senderId();
        final var receiverAddress = (Address) call.get(HRC_RECEIVER);
        final var token = attempt.redirectTokenId();
        validateTrue(token != null, INVALID_TOKEN_ID);
        final var receiverId = attempt.addressIdConverter().convert(receiverAddress);

        return TransactionBody.newBuilder()
                .tokenCancelAirdrop(TokenCancelAirdropTransactionBody.newBuilder()
                        .pendingAirdrops(pendingFTAirdrop(senderId, receiverId, token)))
                .build();
    }

    public TransactionBody decodeCancelAirdropNFT(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenCancelAirdropTranslator.HRC_CANCEL_AIRDROP_NFT.decodeCall(attempt.inputBytes());

        final var senderId = attempt.senderId();
        final var receiverAddress = (Address) call.get(HRC_RECEIVER);
        final var serial = (long) call.get(HRC_SERIAL);
        final var token = attempt.redirectTokenId();
        validateTrue(token != null, INVALID_TOKEN_ID);
        final var receiverId = attempt.addressIdConverter().convert(receiverAddress);

        return TransactionBody.newBuilder()
                .tokenCancelAirdrop(TokenCancelAirdropTransactionBody.newBuilder()
                        .pendingAirdrops(pendingNFTAirdrop(senderId, receiverId, token, serial)))
                .build();
    }
}
