// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withClearedField;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_SPENDER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * A {@link ModificationStrategy} that clears entity ids from the original
 * transaction body.
 */
public class BodyIdClearingStrategy extends IdClearingStrategy<TxnModification> implements TxnModificationStrategy {
    private static final Map<String, ExpectedResponse> NON_SCHEDULED_CLEARED_ID_RESPONSES = Map.ofEntries(
            entry("proto.TransactionID.accountID", ExpectedResponse.atIngest(PAYER_ACCOUNT_NOT_FOUND)),
            entry("proto.TransactionBody.nodeAccountID", ExpectedResponse.atIngest(INVALID_NODE_ACCOUNT)),
            // (FUTURE) Switch to expecting any "atIngest()" response below to atConsensus()
            entry("proto.TokenAssociateTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry(
                    "proto.TokenAssociateTransactionBody.tokens",
                    ExpectedResponse.atConsensusOneOf(SUCCESS, TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)),
            entry(
                    "proto.AccountAmount.accountID",
                    ExpectedResponse.atIngestOneOf(INVALID_ACCOUNT_ID, INVALID_TRANSFER_ACCOUNT_ID)),
            entry("proto.NftTransfer.senderAccountID", ExpectedResponse.atIngest(INVALID_TRANSFER_ACCOUNT_ID)),
            entry("proto.NftTransfer.receiverAccountID", ExpectedResponse.atIngest(INVALID_TRANSFER_ACCOUNT_ID)),
            entry("proto.TokenTransferList.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry(
                    "proto.CryptoUpdateTransactionBody.accountIDToUpdate",
                    ExpectedResponse.atIngest(ACCOUNT_ID_DOES_NOT_EXIST)),
            entry("proto.CryptoUpdateTransactionBody.staked_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ContractCallTransactionBody.contractID", ExpectedResponse.atIngest(INVALID_CONTRACT_ID)),
            entry(
                    "proto.CryptoDeleteTransactionBody.transferAccountID",
                    ExpectedResponse.atIngest(ACCOUNT_ID_DOES_NOT_EXIST)),
            entry(
                    "proto.CryptoDeleteTransactionBody.deleteAccountID",
                    ExpectedResponse.atIngest(ACCOUNT_ID_DOES_NOT_EXIST)),
            entry("proto.ContractCreateTransactionBody.fileID", ExpectedResponse.atConsensus(INVALID_FILE_ID)),
            entry("proto.ContractCreateTransactionBody.auto_renew_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ContractUpdateTransactionBody.contractID", ExpectedResponse.atIngest(INVALID_CONTRACT_ID)),
            entry("proto.ContractUpdateTransactionBody.auto_renew_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ContractUpdateTransactionBody.staked_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.FileAppendTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_FILE_ID)),
            entry("proto.FileUpdateTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_FILE_ID)),
            entry("proto.FileDeleteTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_FILE_ID)),
            entry("proto.CryptoCreateTransactionBody.staked_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.SystemDeleteTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_TRANSACTION_BODY)),
            entry("proto.SystemUndeleteTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_TRANSACTION_BODY)),
            entry("proto.ContractDeleteTransactionBody.contractID", ExpectedResponse.atIngest(INVALID_CONTRACT_ID)),
            entry(
                    "proto.ContractDeleteTransactionBody.transferAccountID",
                    ExpectedResponse.atIngest(OBTAINER_REQUIRED)),
            entry(
                    "proto.ContractDeleteTransactionBody.transferContractID",
                    ExpectedResponse.atIngest(OBTAINER_REQUIRED)),
            entry("proto.ConsensusCreateTopicTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ConsensusUpdateTopicTransactionBody.topicID", ExpectedResponse.atIngest(INVALID_TOPIC_ID)),
            entry("proto.ConsensusUpdateTopicTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ConsensusDeleteTopicTransactionBody.topicID", ExpectedResponse.atIngest(INVALID_TOPIC_ID)),
            entry("proto.ConsensusSubmitMessageTransactionBody.topicID", ExpectedResponse.atIngest(INVALID_TOPIC_ID)),
            entry(
                    "proto.TokenCreateTransactionBody.treasury",
                    ExpectedResponse.atIngest(INVALID_TREASURY_ACCOUNT_FOR_TOKEN)),
            entry("proto.TokenCreateTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry(
                    "proto.CustomFee.fee_collector_account_id",
                    ExpectedResponse.atConsensus(INVALID_CUSTOM_FEE_COLLECTOR)),
            entry("proto.FixedFee.denominating_token_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.TokenFreezeAccountTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenFreezeAccountTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenUnfreezeAccountTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUnfreezeAccountTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenGrantKycTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenGrantKycTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenRevokeKycTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenRevokeKycTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenDeleteTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUpdateTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUpdateTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.TokenMintTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenBurnTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenWipeAccountTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenWipeAccountTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenDissociateTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry(
                    "proto.TokenDissociateTransactionBody.tokens",
                    ExpectedResponse.atConsensusOneOf(SUCCESS, TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)),
            entry("proto.ScheduleCreateTransactionBody.payerAccountID", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ScheduleDeleteTransactionBody.scheduleID", ExpectedResponse.atIngest(INVALID_SCHEDULE_ID)),
            entry("proto.ScheduleSignTransactionBody.scheduleID", ExpectedResponse.atIngest(INVALID_SCHEDULE_ID)),
            entry("proto.TokenFeeScheduleUpdateTransactionBody.token_id", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenPauseTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUnpauseTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.NftAllowance.tokenId", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.NftAllowance.owner", ExpectedResponse.atConsensus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)),
            entry("proto.NftAllowance.spender", ExpectedResponse.atIngest(INVALID_ALLOWANCE_SPENDER_ID)),
            entry("proto.NftAllowance.delegating_spender", ExpectedResponse.atConsensus(INVALID_SIGNATURE)),
            entry("proto.TokenAllowance.tokenId", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenAllowance.owner", ExpectedResponse.atConsensus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)),
            entry("proto.TokenAllowance.spender", ExpectedResponse.atIngest(INVALID_ALLOWANCE_SPENDER_ID)),
            entry("proto.NftRemoveAllowance.token_id", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.NftRemoveAllowance.owner", ExpectedResponse.atConsensus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)),
            entry(
                    "proto.EthereumTransactionBody.call_data",
                    ExpectedResponse.atConsensus(INVALID_ETHEREUM_TRANSACTION)),
            entry("proto.TokenUpdateNftsTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.PendingAirdropId.receiver_id", ExpectedResponse.atIngest(INVALID_PENDING_AIRDROP_ID)),
            entry(
                    "proto.PendingAirdropId.fungible_token_type",
                    ExpectedResponse.atConsensus(INVALID_PENDING_AIRDROP_ID)),
            entry("proto.PendingAirdropId.sender_id", ExpectedResponse.atIngest(INVALID_PENDING_AIRDROP_ID)));

    private static final Map<String, ExpectedResponse> SCHEDULED_CLEARED_ID_RESPONSES = Map.ofEntries(
            entry("proto.AccountAmount.accountID", ExpectedResponse.atConsensusOneOf(INVALID_ACCOUNT_ID)));

    @NonNull
    @Override
    public TxnModification modificationForTarget(@NonNull TargetField targetField, int encounterIndex) {
        final var expectedResponse = targetField.isInScheduledTransaction()
                ? SCHEDULED_CLEARED_ID_RESPONSES.get(targetField.name())
                : NON_SCHEDULED_CLEARED_ID_RESPONSES.get(targetField.name());
        requireNonNull(
                expectedResponse,
                "No expected response for " + (targetField.isInScheduledTransaction() ? "scheduled " : "")
                        + "field "
                        + targetField.name());
        return new TxnModification(
                "Clearing "
                        + (targetField.isInScheduledTransaction() ? "scheduled " : "")
                        + "field " + targetField.name() + " (#" + encounterIndex + ")",
                BodyMutation.withTransform(b -> withClearedField(b, targetField.descriptor(), encounterIndex)),
                expectedResponse);
    }
}
