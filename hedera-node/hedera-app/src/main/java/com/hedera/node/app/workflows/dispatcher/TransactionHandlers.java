// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.dispatcher;

import com.hedera.node.app.hints.handlers.CrsPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsKeyPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.hints.handlers.HintsPreprocessingVoteHandler;
import com.hedera.node.app.history.handlers.HistoryProofKeyPublicationHandler;
import com.hedera.node.app.history.handlers.HistoryProofSignatureHandler;
import com.hedera.node.app.history.handlers.HistoryProofVoteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeCreateHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeDeleteHandler;
import com.hedera.node.app.service.addressbook.impl.handlers.NodeUpdateHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusCreateTopicHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusDeleteTopicHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusSubmitMessageHandler;
import com.hedera.node.app.service.consensus.impl.handlers.ConsensusUpdateTopicHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractCreateHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractDeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractSystemDeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractSystemUndeleteHandler;
import com.hedera.node.app.service.contract.impl.handlers.ContractUpdateHandler;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.contract.impl.handlers.HookDispatchHandler;
import com.hedera.node.app.service.contract.impl.handlers.LambdaSStoreHandler;
import com.hedera.node.app.service.file.impl.handlers.FileAppendHandler;
import com.hedera.node.app.service.file.impl.handlers.FileCreateHandler;
import com.hedera.node.app.service.file.impl.handlers.FileDeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileSystemDeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileSystemUndeleteHandler;
import com.hedera.node.app.service.file.impl.handlers.FileUpdateHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.FreezeHandler;
import com.hedera.node.app.service.networkadmin.impl.handlers.NetworkUncheckedSubmitHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleCreateHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleDeleteHandler;
import com.hedera.node.app.service.schedule.impl.handlers.ScheduleSignHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoAddLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoApproveAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteAllowanceHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteLiveHashHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.CryptoUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAccountWipeHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenAssociateToAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenBurnHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenCancelAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenClaimAirdropHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenCreateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDeleteHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDissociateFromAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFeeScheduleUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenFreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenGrantKycToAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenMintHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenPauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenRejectHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenRevokeKycFromAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnfreezeAccountHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUnpauseHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenUpdateNftsHandler;
import com.hedera.node.app.service.util.impl.handlers.AtomicBatchHandler;
import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A record that contains all {@link com.hedera.node.app.spi.workflows.TransactionHandler}s that are available in the
 * app
 */
public record TransactionHandlers(
        @NonNull ConsensusCreateTopicHandler consensusCreateTopicHandler,
        @NonNull ConsensusUpdateTopicHandler consensusUpdateTopicHandler,
        @NonNull ConsensusDeleteTopicHandler consensusDeleteTopicHandler,
        @NonNull ConsensusSubmitMessageHandler consensusSubmitMessageHandler,
        @NonNull ContractCreateHandler contractCreateHandler,
        @NonNull ContractUpdateHandler contractUpdateHandler,
        @NonNull ContractCallHandler contractCallHandler,
        @NonNull ContractDeleteHandler contractDeleteHandler,
        @NonNull ContractSystemDeleteHandler contractSystemDeleteHandler,
        @NonNull ContractSystemUndeleteHandler contractSystemUndeleteHandler,
        @NonNull EthereumTransactionHandler ethereumTransactionHandler,
        @NonNull LambdaSStoreHandler lambdaSStoreHandler,
        @NonNull HookDispatchHandler hookDispatchHandler,
        @NonNull CryptoCreateHandler cryptoCreateHandler,
        @NonNull CryptoUpdateHandler cryptoUpdateHandler,
        @NonNull CryptoTransferHandler cryptoTransferHandler,
        @NonNull CryptoDeleteHandler cryptoDeleteHandler,
        @NonNull CryptoApproveAllowanceHandler cryptoApproveAllowanceHandler,
        @NonNull CryptoDeleteAllowanceHandler cryptoDeleteAllowanceHandler,
        @NonNull CryptoAddLiveHashHandler cryptoAddLiveHashHandler,
        @NonNull CryptoDeleteLiveHashHandler cryptoDeleteLiveHashHandler,
        @NonNull FileCreateHandler fileCreateHandler,
        @NonNull FileUpdateHandler fileUpdateHandler,
        @NonNull FileDeleteHandler fileDeleteHandler,
        @NonNull FileAppendHandler fileAppendHandler,
        @NonNull FileSystemDeleteHandler fileSystemDeleteHandler,
        @NonNull FileSystemUndeleteHandler fileSystemUndeleteHandler,
        @NonNull FreezeHandler freezeHandler,
        @NonNull NetworkUncheckedSubmitHandler networkUncheckedSubmitHandler,
        @NonNull ScheduleCreateHandler scheduleCreateHandler,
        @NonNull ScheduleSignHandler scheduleSignHandler,
        @NonNull ScheduleDeleteHandler scheduleDeleteHandler,
        @NonNull TokenCreateHandler tokenCreateHandler,
        @NonNull TokenUpdateHandler tokenUpdateHandler,
        @NonNull TokenMintHandler tokenMintHandler,
        @NonNull TokenBurnHandler tokenBurnHandler,
        @NonNull TokenDeleteHandler tokenDeleteHandler,
        @NonNull TokenAccountWipeHandler tokenAccountWipeHandler,
        @NonNull TokenFreezeAccountHandler tokenFreezeAccountHandler,
        @NonNull TokenUnfreezeAccountHandler tokenUnfreezeAccountHandler,
        @NonNull TokenGrantKycToAccountHandler tokenGrantKycToAccountHandler,
        @NonNull TokenRevokeKycFromAccountHandler tokenRevokeKycFromAccountHandler,
        @NonNull TokenAssociateToAccountHandler tokenAssociateToAccountHandler,
        @NonNull TokenDissociateFromAccountHandler tokenDissociateFromAccountHandler,
        @NonNull TokenFeeScheduleUpdateHandler tokenFeeScheduleUpdateHandler,
        @NonNull TokenPauseHandler tokenPauseHandler,
        @NonNull TokenUnpauseHandler tokenUnpauseHandler,
        @NonNull TokenUpdateNftsHandler tokenUpdateNftsHandler,
        @NonNull TokenRejectHandler tokenRejectHandler,
        @NonNull TokenAirdropHandler tokenAirdropHandler,
        @NonNull TokenCancelAirdropHandler tokenCancelAirdropHandler,
        @NonNull NodeCreateHandler nodeCreateHandler,
        @NonNull NodeUpdateHandler nodeUpdateHandler,
        @NonNull NodeDeleteHandler nodeDeleteHandler,
        @NonNull TokenClaimAirdropHandler tokenClaimAirdropHandler,
        @NonNull HintsKeyPublicationHandler hintsKeyPublicationHandler,
        @NonNull HintsPreprocessingVoteHandler hintsPreprocessingVoteHandler,
        @NonNull HintsPartialSignatureHandler hintsPartialSignatureHandler,
        @NonNull UtilPrngHandler utilPrngHandler,
        @NonNull AtomicBatchHandler atomicBatchHandler,
        @NonNull HistoryProofKeyPublicationHandler historyProofKeyPublicationHandler,
        @NonNull HistoryProofSignatureHandler historyProofSignatureHandler,
        @NonNull HistoryProofVoteHandler historyProofVoteHandler,
        @NonNull CrsPublicationHandler crsPublicationHandler) {}
