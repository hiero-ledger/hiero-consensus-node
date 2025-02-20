// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Wrapper class for all handlers of the token service. This should be a {@code record} but it looks like Dagger does
 * not support Java records
 */
@Singleton
// Suppressing the warning that this class is too big
@SuppressWarnings("java:S6539")
public class TokenHandlers {

    private final CryptoCreateHandler cryptoCreateHandler;
    private final CryptoUpdateHandler cryptoUpdateHandler;
    private final CryptoTransferHandler cryptoTransferHandler;
    private final CryptoDeleteHandler cryptoDeleteHandler;
    private final CryptoApproveAllowanceHandler cryptoApproveAllowanceHandler;
    private final CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler;
    private final CryptoAddLiveHashHandler cryptoAddLiveHashHandler;
    private final CryptoDeleteLiveHashHandler cryptoDeleteLiveHashHandler;
    private final TokenCreateHandler tokenCreateHandler;
    private final TokenUpdateHandler tokenUpdateHandler;
    private final TokenMintHandler tokenMintHandler;
    private final TokenBurnHandler tokenBurnHandler;
    private final TokenDeleteHandler tokenDeleteHandler;
    private final TokenAccountWipeHandler tokenAccountWipeHandler;
    private final TokenFreezeAccountHandler tokenFreezeAccountHandler;
    private final TokenUnfreezeAccountHandler tokenUnfreezeAccountHandler;
    private final TokenGrantKycToAccountHandler tokenGrantKycToAccountHandler;
    private final TokenRevokeKycFromAccountHandler tokenRevokeKycFromAccountHandler;
    private final TokenAssociateToAccountHandler tokenAssociateToAccountHandler;
    private final TokenDissociateFromAccountHandler tokenDissociateFromAccountHandler;
    private final TokenFeeScheduleUpdateHandler tokenFeeScheduleUpdateHandler;
    private final TokenPauseHandler tokenPauseHandler;
    private final TokenUnpauseHandler tokenUnpauseHandler;
    private final CryptoGetAccountBalanceHandler cryptoGetAccountBalanceHandler;
    private final CryptoGetAccountInfoHandler cryptoGetAccountInfoHandler;
    private final CryptoGetAccountRecordsHandler cryptoGetAccountRecordsHandler;
    private final CryptoGetLiveHashHandler cryptoGetLiveHashHandler;
    private final CryptoGetStakersHandler cryptoGetStakersHandler;
    private final TokenGetInfoHandler tokenGetInfoHandler;
    private final TokenGetAccountNftInfosHandler tokenGetAccountNftInfosHandler;
    private final TokenGetNftInfoHandler tokenGetNftInfoHandler;
    private final TokenGetNftInfosHandler tokenGetNftInfosHandler;
    private final TokenUpdateNftsHandler tokenUpdateNftsHandler;
    private final TokenAirdropHandler tokenAirdropHandler;
    private final TokenRejectHandler tokenRejectHandler;
    private final TokenCancelAirdropHandler tokenCancelAirdropHandler;
    private final TokenClaimAirdropHandler tokenClaimAirdropHandler;

    /**
     * Constructor for the TokenHandlers.
     * @param cryptoCreateHandler crypto create handler
     * @param cryptoUpdateHandler crypto update handler
     * @param cryptoTransferHandler crypto transfer handler
     * @param cryptoDeleteHandler crypto delete handler
     * @param cryptoApproveAllowanceHandler crypto approve allowance handler
     * @param cryptoDeleteAllowanceHandler crypto delete allowance handler
     * @param cryptoAddLiveHashHandler crypto add live hash handler
     * @param cryptoDeleteLiveHashHandler crypto delete live hash handler
     * @param tokenCreateHandler token create handler
     * @param tokenUpdateHandler token update handler
     * @param tokenMintHandler token mint handler
     * @param tokenBurnHandler token burn handler
     * @param tokenDeleteHandler token delete handler
     * @param tokenAccountWipeHandler token account wipe handler
     * @param tokenFreezeAccountHandler token freeze account handler
     * @param tokenUnfreezeAccountHandler token unfreeze account handler
     * @param tokenGrantKycToAccountHandler token grant kyc to account handler
     * @param tokenRevokeKycFromAccountHandler token revoke kyc from account handler
     * @param tokenAssociateToAccountHandler token associate to account handler
     * @param tokenDissociateFromAccountHandler token dissociate from account handler
     * @param tokenFeeScheduleUpdateHandler token fee schedule update handler
     * @param tokenPauseHandler token pause handler
     * @param tokenUnpauseHandler token unpause handler
     * @param cryptoGetAccountBalanceHandler crypto get account balance handler
     * @param cryptoGetAccountInfoHandler crypto get account info handler
     * @param cryptoGetAccountRecordsHandler crypto get account records handler
     * @param cryptoGetLiveHashHandler crypto get live hash handler
     * @param cryptoGetStakersHandler crypto get stakers handler
     * @param tokenGetInfoHandler token get info handler
     * @param tokenGetAccountNftInfosHandler token get account nft infos handler
     * @param tokenGetNftInfoHandler token get nft info handler
     * @param tokenGetNftInfosHandler token get nft infos handler
     * @param tokenUpdateNftsHandler token update nfts handler
     * @param tokenRejectHandler token reject handler
     * @param tokenCancelAirdropHandler token cancel airdrop handler
     * @param tokenClaimAirdropHandler token claim airdrop handler
     */
    @Inject
    public TokenHandlers(
            @NonNull final CryptoCreateHandler cryptoCreateHandler,
            @NonNull final CryptoUpdateHandler cryptoUpdateHandler,
            @NonNull final CryptoTransferHandler cryptoTransferHandler,
            @NonNull final CryptoDeleteHandler cryptoDeleteHandler,
            @NonNull final CryptoApproveAllowanceHandler cryptoApproveAllowanceHandler,
            @NonNull final CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler,
            @NonNull final CryptoAddLiveHashHandler cryptoAddLiveHashHandler,
            @NonNull final CryptoDeleteLiveHashHandler cryptoDeleteLiveHashHandler,
            @NonNull final TokenCreateHandler tokenCreateHandler,
            @NonNull final TokenUpdateHandler tokenUpdateHandler,
            @NonNull final TokenMintHandler tokenMintHandler,
            @NonNull final TokenBurnHandler tokenBurnHandler,
            @NonNull final TokenDeleteHandler tokenDeleteHandler,
            @NonNull final TokenAccountWipeHandler tokenAccountWipeHandler,
            @NonNull final TokenFreezeAccountHandler tokenFreezeAccountHandler,
            @NonNull final TokenUnfreezeAccountHandler tokenUnfreezeAccountHandler,
            @NonNull final TokenGrantKycToAccountHandler tokenGrantKycToAccountHandler,
            @NonNull final TokenRevokeKycFromAccountHandler tokenRevokeKycFromAccountHandler,
            @NonNull final TokenAssociateToAccountHandler tokenAssociateToAccountHandler,
            @NonNull final TokenDissociateFromAccountHandler tokenDissociateFromAccountHandler,
            @NonNull final TokenFeeScheduleUpdateHandler tokenFeeScheduleUpdateHandler,
            @NonNull final TokenPauseHandler tokenPauseHandler,
            @NonNull final TokenUnpauseHandler tokenUnpauseHandler,
            @NonNull final CryptoGetAccountBalanceHandler cryptoGetAccountBalanceHandler,
            @NonNull final CryptoGetAccountInfoHandler cryptoGetAccountInfoHandler,
            @NonNull final CryptoGetAccountRecordsHandler cryptoGetAccountRecordsHandler,
            @NonNull final CryptoGetLiveHashHandler cryptoGetLiveHashHandler,
            @NonNull final CryptoGetStakersHandler cryptoGetStakersHandler,
            @NonNull final TokenGetInfoHandler tokenGetInfoHandler,
            @NonNull final TokenGetAccountNftInfosHandler tokenGetAccountNftInfosHandler,
            @NonNull final TokenGetNftInfoHandler tokenGetNftInfoHandler,
            @NonNull final TokenGetNftInfosHandler tokenGetNftInfosHandler,
            @NonNull final TokenRejectHandler tokenRejectHandler,
            @NonNull final TokenUpdateNftsHandler tokenUpdateNftsHandler,
            @NonNull final TokenCancelAirdropHandler tokenCancelAirdropHandler,
            @NonNull final TokenClaimAirdropHandler tokenClaimAirdropHandler,
            @NonNull final TokenAirdropHandler tokenAirdropHandler) {
        this.cryptoCreateHandler = Objects.requireNonNull(cryptoCreateHandler, "cryptoCreateHandler must not be null");
        this.cryptoUpdateHandler = Objects.requireNonNull(cryptoUpdateHandler, "cryptoUpdateHandler must not be null");
        this.cryptoTransferHandler =
                Objects.requireNonNull(cryptoTransferHandler, "cryptoTransferHandler must not be null");
        this.cryptoDeleteHandler = Objects.requireNonNull(cryptoDeleteHandler, "cryptoDeleteHandler must not be null");
        this.cryptoApproveAllowanceHandler =
                Objects.requireNonNull(cryptoApproveAllowanceHandler, "cryptoApproveAllowanceHandler must not be null");
        this.cryptoDeleteAllowanceHandler =
                Objects.requireNonNull(cryptoDeleteAllowanceHandler, "cryptoDeleteAllowanceHandler must not be null");
        this.cryptoAddLiveHashHandler =
                Objects.requireNonNull(cryptoAddLiveHashHandler, "cryptoAddLiveHashHandler must not be null");
        this.cryptoDeleteLiveHashHandler =
                Objects.requireNonNull(cryptoDeleteLiveHashHandler, "cryptoDeleteLiveHashHandler must not be null");
        this.tokenCreateHandler = Objects.requireNonNull(tokenCreateHandler, "tokenCreateHandler must not be null");
        this.tokenUpdateHandler = Objects.requireNonNull(tokenUpdateHandler, "tokenUpdateHandler must not be null");
        this.tokenMintHandler = Objects.requireNonNull(tokenMintHandler, "tokenMintHandler must not be null");
        this.tokenBurnHandler = Objects.requireNonNull(tokenBurnHandler, "tokenBurnHandler must not be null");
        this.tokenDeleteHandler = Objects.requireNonNull(tokenDeleteHandler, "tokenDeleteHandler must not be null");
        this.tokenAccountWipeHandler =
                Objects.requireNonNull(tokenAccountWipeHandler, "tokenAccountWipeHandler must not be null");
        this.tokenFreezeAccountHandler =
                Objects.requireNonNull(tokenFreezeAccountHandler, "tokenFreezeAccountHandler must not be null");
        this.tokenUnfreezeAccountHandler =
                Objects.requireNonNull(tokenUnfreezeAccountHandler, "tokenUnfreezeAccountHandler must not be null");
        this.tokenGrantKycToAccountHandler =
                Objects.requireNonNull(tokenGrantKycToAccountHandler, "tokenGrantKycToAccountHandler must not be null");
        this.tokenRevokeKycFromAccountHandler = Objects.requireNonNull(
                tokenRevokeKycFromAccountHandler, "tokenRevokeKycFromAccountHandler must not be null");
        this.tokenAssociateToAccountHandler = Objects.requireNonNull(
                tokenAssociateToAccountHandler, "tokenAssociateToAccountHandler must not be null");
        this.tokenDissociateFromAccountHandler = Objects.requireNonNull(
                tokenDissociateFromAccountHandler, "tokenDissociateFromAccountHandler must not be null");
        this.tokenFeeScheduleUpdateHandler =
                Objects.requireNonNull(tokenFeeScheduleUpdateHandler, "tokenFeeScheduleUpdateHandler must not be null");
        this.tokenPauseHandler = Objects.requireNonNull(tokenPauseHandler, "tokenPauseHandler must not be null");
        this.tokenUnpauseHandler = Objects.requireNonNull(tokenUnpauseHandler, "tokenUnpauseHandler must not be null");
        this.cryptoGetAccountBalanceHandler = Objects.requireNonNull(
                cryptoGetAccountBalanceHandler, "cryptoGetAccountBalanceHandler must not be null");
        this.cryptoGetAccountInfoHandler =
                Objects.requireNonNull(cryptoGetAccountInfoHandler, "cryptoGetAccountInfoHandler must not be null");
        this.cryptoGetAccountRecordsHandler = Objects.requireNonNull(
                cryptoGetAccountRecordsHandler, "cryptoGetAccountRecordsHandler must not be null");
        this.cryptoGetLiveHashHandler =
                Objects.requireNonNull(cryptoGetLiveHashHandler, "cryptoGetLiveHashHandler must not be null");
        this.cryptoGetStakersHandler =
                Objects.requireNonNull(cryptoGetStakersHandler, "cryptoGetStakersHandler must not be null");
        this.tokenGetInfoHandler = Objects.requireNonNull(tokenGetInfoHandler, "tokenGetInfoHandler must not be null");
        this.tokenGetAccountNftInfosHandler = Objects.requireNonNull(
                tokenGetAccountNftInfosHandler, "tokenGetAccountNftInfosHandler must not be null");
        this.tokenGetNftInfoHandler =
                Objects.requireNonNull(tokenGetNftInfoHandler, "tokenGetNftInfoHandler must not be null");
        this.tokenGetNftInfosHandler =
                Objects.requireNonNull(tokenGetNftInfosHandler, "tokenGetNftInfosHandler must not be null");
        this.tokenUpdateNftsHandler =
                Objects.requireNonNull(tokenUpdateNftsHandler, "tokenUpdateNftsHandler must not be null");
        this.tokenRejectHandler = Objects.requireNonNull(tokenRejectHandler, "tokenRejectHandler must not be null");
        this.tokenAirdropHandler = Objects.requireNonNull(tokenAirdropHandler, "tokenAirdropsHandler must not be null");
        this.tokenCancelAirdropHandler =
                Objects.requireNonNull(tokenCancelAirdropHandler, "tokenCancelAirdropHandler must not be null");
        this.tokenClaimAirdropHandler =
                Objects.requireNonNull(tokenClaimAirdropHandler, "tokenClaimAirdropHandler must not be null");
    }

    /**
     * Gets the cryptoCreateHandler.
     *
     * @return the cryptoCreateHandler
     */
    public CryptoCreateHandler cryptoCreateHandler() {
        return cryptoCreateHandler;
    }

    /**
     * Gets the cryptoUpdateHandler.
     *
     * @return the cryptoUpdateHandler
     */
    public CryptoUpdateHandler cryptoUpdateHandler() {
        return cryptoUpdateHandler;
    }

    /**
     * Gets the cryptoTransferHandler.
     *
     * @return the cryptoTransferHandler
     */
    public CryptoTransferHandler cryptoTransferHandler() {
        return cryptoTransferHandler;
    }

    /**
     * Gets the cryptoDeleteHandler.
     *
     * @return the cryptoDeleteHandler
     */
    public CryptoDeleteHandler cryptoDeleteHandler() {
        return cryptoDeleteHandler;
    }

    /**
     * Gets the cryptoApproveAllowanceHandler.
     *
     * @return the cryptoApproveAllowanceHandler
     */
    public CryptoApproveAllowanceHandler cryptoApproveAllowanceHandler() {
        return cryptoApproveAllowanceHandler;
    }

    /**
     * Gets the cryptoDeleteAllowanceHandler.
     *
     * @return the cryptoDeleteAllowanceHandler
     */
    public CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler() {
        return cryptoDeleteAllowanceHandler;
    }

    /**
     * Gets the cryptoAddLiveHashHandler.
     *
     * @return the cryptoAddLiveHashHandler
     */
    public CryptoAddLiveHashHandler cryptoAddLiveHashHandler() {
        return cryptoAddLiveHashHandler;
    }

    /**
     * Gets the cryptoDeleteLiveHashHandler.
     *
     * @return the cryptoDeleteLiveHashHandler
     */
    public CryptoDeleteLiveHashHandler cryptoDeleteLiveHashHandler() {
        return cryptoDeleteLiveHashHandler;
    }

    /**
     * Gets the tokenCreateHandler.
     *
     * @return the tokenCreateHandler
     */
    public TokenCreateHandler tokenCreateHandler() {
        return tokenCreateHandler;
    }

    /**
     * Gets the tokenUpdateHandler.
     *
     * @return the tokenUpdateHandler
     */
    public TokenUpdateHandler tokenUpdateHandler() {
        return tokenUpdateHandler;
    }

    /**
     * Gets the tokenMintHandler.
     *
     * @return the tokenMintHandler
     */
    public TokenMintHandler tokenMintHandler() {
        return tokenMintHandler;
    }

    /**
     * Gets the tokenBurnHandler.
     *
     * @return the tokenBurnHandler
     */
    public TokenBurnHandler tokenBurnHandler() {
        return tokenBurnHandler;
    }

    /**
     * Gets the tokenDeleteHandler.
     *
     * @return the tokenDeleteHandler
     */
    public TokenDeleteHandler tokenDeleteHandler() {
        return tokenDeleteHandler;
    }

    /**
     * Gets the tokenAccountWipeHandler.
     *
     * @return the tokenAccountWipeHandler
     */
    public TokenAccountWipeHandler tokenAccountWipeHandler() {
        return tokenAccountWipeHandler;
    }

    /**
     * Gets the tokenFreezeAccountHandler.
     *
     * @return the tokenFreezeAccountHandler
     */
    public TokenFreezeAccountHandler tokenFreezeAccountHandler() {
        return tokenFreezeAccountHandler;
    }

    /**
     * Gets the tokenUnfreezeAccountHandler.
     *
     * @return the tokenUnfreezeAccountHandler
     */
    public TokenUnfreezeAccountHandler tokenUnfreezeAccountHandler() {
        return tokenUnfreezeAccountHandler;
    }

    /**
     * Gets the tokenGrantKycToAccountHandler.
     *
     * @return the tokenGrantKycToAccountHandler
     */
    public TokenGrantKycToAccountHandler tokenGrantKycToAccountHandler() {
        return tokenGrantKycToAccountHandler;
    }

    /**
     * Gets the tokenRevokeKycFromAccountHandler.
     *
     * @return the tokenRevokeKycFromAccountHandler
     */
    public TokenRevokeKycFromAccountHandler tokenRevokeKycFromAccountHandler() {
        return tokenRevokeKycFromAccountHandler;
    }

    /**
     * Gets the tokenAssociateToAccountHandler.
     *
     * @return the tokenAssociateToAccountHandler
     */
    public TokenAssociateToAccountHandler tokenAssociateToAccountHandler() {
        return tokenAssociateToAccountHandler;
    }

    /**
     * Gets the tokenDissociateFromAccountHandler.
     *
     * @return the tokenDissociateFromAccountHandler
     */
    public TokenDissociateFromAccountHandler tokenDissociateFromAccountHandler() {
        return tokenDissociateFromAccountHandler;
    }

    /**
     * Gets the tokenFeeScheduleUpdateHandler.
     *
     * @return the tokenFeeScheduleUpdateHandler
     */
    public TokenFeeScheduleUpdateHandler tokenFeeScheduleUpdateHandler() {
        return tokenFeeScheduleUpdateHandler;
    }

    /**
     * Gets the tokenPauseHandler.
     *
     * @return the tokenPauseHandler
     */
    public TokenPauseHandler tokenPauseHandler() {
        return tokenPauseHandler;
    }

    /**
     * Gets the tokenUnpauseHandler.
     *
     * @return the tokenUnpauseHandler
     */
    public TokenUnpauseHandler tokenUnpauseHandler() {
        return tokenUnpauseHandler;
    }

    /**
     * Gets the cryptoGetAccountBalanceHandler.
     *
     * @return the cryptoGetAccountBalanceHandler
     */
    public CryptoGetAccountBalanceHandler cryptoGetAccountBalanceHandler() {
        return cryptoGetAccountBalanceHandler;
    }

    /**
     * Gets the cryptoGetAccountInfoHandler.
     *
     * @return the cryptoGetAccountInfoHandler
     */
    public CryptoGetAccountInfoHandler cryptoGetAccountInfoHandler() {
        return cryptoGetAccountInfoHandler;
    }

    /**
     * Gets the cryptoGetAccountRecordsHandler.
     *
     * @return the cryptoGetAccountRecordsHandler
     */
    public CryptoGetAccountRecordsHandler cryptoGetAccountRecordsHandler() {
        return cryptoGetAccountRecordsHandler;
    }

    /**
     * Gets the cryptoGetLiveHashHandler.
     *
     * @return the cryptoGetLiveHashHandler
     */
    public CryptoGetLiveHashHandler cryptoGetLiveHashHandler() {
        return cryptoGetLiveHashHandler;
    }

    /**
     * Gets the cryptoGetStakersHandler.
     *
     * @return the cryptoGetStakersHandler
     */
    public CryptoGetStakersHandler cryptoGetStakersHandler() {
        return cryptoGetStakersHandler;
    }

    /**
     * Gets the tokenGetInfoHandler.
     *
     * @return the tokenGetInfoHandler
     */
    public TokenGetInfoHandler tokenGetInfoHandler() {
        return tokenGetInfoHandler;
    }

    /**
     * Gets the tokenGetAccountNftInfosHandler.
     *
     * @return the tokenGetAccountNftInfosHandler
     */
    public TokenGetAccountNftInfosHandler tokenGetAccountNftInfosHandler() {
        return tokenGetAccountNftInfosHandler;
    }

    /**
     * Gets the tokenGetNftInfoHandler.
     *
     * @return the tokenGetNftInfoHandler
     */
    public TokenGetNftInfoHandler tokenGetNftInfoHandler() {
        return tokenGetNftInfoHandler;
    }

    /**
     * Gets the tokenGetNftInfosHandler.
     *
     * @return the tokenGetNftInfosHandler
     */
    public TokenGetNftInfosHandler tokenGetNftInfosHandler() {
        return tokenGetNftInfosHandler;
    }

    /**
     * Gets the tokenUpdateNftsHandler.
     *
     * @return the tokenUpdateNftsHandler
     */
    public TokenUpdateNftsHandler tokenUpdateNftsHandler() {
        return tokenUpdateNftsHandler;
    }

    /**
     * Gets the tokenRejectHandler.
     *
     * @return the tokenRejectHandler
     */
    public TokenRejectHandler tokenRejectHandler() {
        return tokenRejectHandler;
    }
    /**
     * Gets the tokenCancelAirdropHandler.
     *
     * @return the tokenCancelAirdropHandler
     */
    public TokenCancelAirdropHandler tokenCancelAirdropHandler() {
        return tokenCancelAirdropHandler;
    }
    /**
     * Gets the tokenClaimAirdropHandler.
     * @return the tokenClaimAirdropHandler
     */
    public TokenClaimAirdropHandler tokenClaimAirdropHandler() {
        return tokenClaimAirdropHandler;
    }

    public TokenAirdropHandler tokenAirdropsHandler() {
        return tokenAirdropHandler;
    }
}
