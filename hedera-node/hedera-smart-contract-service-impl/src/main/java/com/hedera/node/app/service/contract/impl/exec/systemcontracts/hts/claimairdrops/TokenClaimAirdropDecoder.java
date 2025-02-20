// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hedera.node.app.service.contract.impl.exec.utils.PendingAirdropsHelper.decodePendingAirdrops;
import static com.hedera.node.app.service.contract.impl.exec.utils.PendingAirdropsHelper.pendingFTAirdrop;
import static com.hedera.node.app.service.contract.impl.exec.utils.PendingAirdropsHelper.pendingNFTAirdrop;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.token.TokenClaimAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TokenClaimAirdropDecoder {

    // Tuple indexes
    private static final int TRANSFER_LIST = 0;
    private static final int SENDER = 0;
    private static final int RECEIVER = 1;
    private static final int TOKEN = 2;
    private static final int SERIAL = 3;
    private static final int HRC_SENDER = 0;
    private static final int HRC_SERIAL = 1;

    @Inject
    public TokenClaimAirdropDecoder() {
        // Dagger2
    }

    public TransactionBody decodeTokenClaimAirdrop(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenClaimAirdropTranslator.CLAIM_AIRDROPS.decodeCall(attempt.inputBytes());
        final var maxPendingAirdropsToClaim =
                attempt.configuration().getConfigData(TokensConfig.class).maxAllowedPendingAirdropsToClaim();
        final var transferList = (Tuple[]) call.get(TRANSFER_LIST);
        validateFalse(transferList.length > maxPendingAirdropsToClaim, PENDING_AIRDROP_ID_LIST_TOO_LONG);

        final var pendingAirdrops = decodePendingAirdrops(attempt, transferList);

        return TransactionBody.newBuilder()
                .tokenClaimAirdrop(TokenClaimAirdropTransactionBody.newBuilder().pendingAirdrops(pendingAirdrops))
                .build();
    }

    public TransactionBody decodeHrcClaimAirdropFt(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_FT.decodeCall(attempt.inputBytes());

        // As the Token Claim is an operation for the receiver of an Airdrop,
        // hence the `transaction sender` in the HRC scenario is in reality the `Airdrop receiver`.
        final var receiverId = attempt.senderId();
        final var senderAddress = (Address) call.get(HRC_SENDER);
        final var token = attempt.redirectTokenId();
        validateTrue(token != null, INVALID_TOKEN_ID);
        final var senderId = attempt.addressIdConverter().convert(senderAddress);

        return TransactionBody.newBuilder()
                .tokenClaimAirdrop(TokenClaimAirdropTransactionBody.newBuilder()
                        .pendingAirdrops(pendingFTAirdrop(senderId, receiverId, token)))
                .build();
    }

    public TransactionBody decodeHrcClaimAirdropNft(@NonNull final HtsCallAttempt attempt) {
        final var call = TokenClaimAirdropTranslator.HRC_CLAIM_AIRDROP_NFT.decodeCall(attempt.inputBytes());

        // As the Token Claim is an operation for the receiver of an Airdrop,
        // hence the `transaction sender` in the HRC scenario is in reality the `Airdrop receiver`.
        final var receiverId = attempt.senderId();
        final var senderAddress = (Address) call.get(HRC_SENDER);
        final var serial = (long) call.get(HRC_SERIAL);
        final var token = attempt.redirectTokenId();
        validateTrue(token != null, INVALID_TOKEN_ID);
        final var senderId = attempt.addressIdConverter().convert(senderAddress);

        return TransactionBody.newBuilder()
                .tokenClaimAirdrop(TokenClaimAirdropTransactionBody.newBuilder()
                        .pendingAirdrops(pendingNFTAirdrop(senderId, receiverId, token, serial)))
                .build();
    }
}
