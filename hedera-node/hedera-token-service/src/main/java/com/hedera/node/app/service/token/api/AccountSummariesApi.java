// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.api;

import static com.hedera.hapi.node.base.TokenFreezeStatus.FREEZE_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenFreezeStatus.FROZEN;
import static com.hedera.hapi.node.base.TokenFreezeStatus.UNFROZEN;
import static com.hedera.hapi.node.base.TokenKycStatus.GRANTED;
import static com.hedera.hapi.node.base.TokenKycStatus.KYC_NOT_APPLICABLE;
import static com.hedera.hapi.node.base.TokenKycStatus.REVOKED;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.epochSecondAtStartOfPeriod;
import static com.hedera.node.app.service.token.api.StakingRewardsApi.estimatePendingReward;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.hex;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.StakingInfo;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenFreezeStatus;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenKycStatus;
import com.hedera.hapi.node.base.TokenRelationship;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Methods for summarizing an account's relationships and attributes to HAPI clients.
 */
public interface AccountSummariesApi {
    /**
     * The size of an EVM address.
     */
    int EVM_ADDRESS_SIZE = 20;
    /**
     * The sentinel node id to represent stakingNodeId is absent on account.
     * It is -1 because nodeId 0 is allowed for network.
     */
    long SENTINEL_NODE_ID = -1L;
    /**
     * The sentinel account id to represent stakedAccountId is absent on account.
     */
    AccountID SENTINEL_ACCOUNT_ID = AccountID.newBuilder().accountNum(0).build();

    /**
     * Returns the hexed EVM address of the given account.
     *
     * @param account the account to be calculated from
     * @return the hexed EVM address of the given account
     */
    static String hexedEvmAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        final var accountId = account.accountIdOrThrow();
        final var arbitraryEvmAddress = extractEvmAddress(account.alias());
        final var evmAddress = arbitraryEvmAddress != null
                ? arbitraryEvmAddress.toByteArray()
                : asEvmAddress(accountId.accountNumOrThrow());
        return hex(evmAddress);
    }

    /**
     * Returns up to the given limit of token relationships for the given account, relative to the provided
     * {@link ReadableTokenStore} and {@link ReadableTokenRelationStore}.
     *
     * @param account the account to get token relationships for
     * @param readableTokenStore the readable token store
     * @param tokenRelationStore the readable token relation store
     * @param limit the maximum number of token relationships to return
     * @return the token relationships for the given account
     */
    static List<TokenRelationship> tokenRelationshipsOf(
            @NonNull final Account account,
            @NonNull final ReadableTokenStore readableTokenStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            final long limit) {
        requireNonNull(account);
        requireNonNull(tokenRelationStore);
        requireNonNull(readableTokenStore);

        final var ret = new ArrayList<TokenRelationship>();
        var tokenId = account.headTokenId();
        int count = 0;
        TokenRelation tokenRelation;
        Token token; // token from readableToken store by tokenID
        AccountID accountID; // build from accountNumber
        while (tokenId != null && !tokenId.equals(TokenID.DEFAULT) && count < limit) {
            accountID = account.accountId();
            tokenRelation = tokenRelationStore.get(accountID, tokenId);
            if (tokenRelation != null) {
                token = readableTokenStore.get(tokenId);
                if (token != null) {
                    addTokenRelation(ret, token, tokenRelation, tokenId);
                }
                tokenId = tokenRelation.nextToken();
            } else {
                break;
            }
            count++;
        }
        return ret;
    }

    private static void addTokenRelation(
            List<TokenRelationship> ret, Token token, TokenRelation tokenRelation, TokenID tokenId) {
        TokenFreezeStatus freezeStatus = FREEZE_NOT_APPLICABLE;
        if (token.hasFreezeKey()) {
            freezeStatus = tokenRelation.frozen() ? FROZEN : UNFROZEN;
        }

        TokenKycStatus kycStatus = KYC_NOT_APPLICABLE;
        if (token.hasKycKey()) {
            kycStatus = tokenRelation.kycGranted() ? GRANTED : REVOKED;
        }

        final var tokenRelationship = TokenRelationship.newBuilder()
                .tokenId(tokenId)
                .symbol(token.symbol())
                .balance(tokenRelation.balance())
                .decimals(token.decimals())
                .kycStatus(kycStatus)
                .freezeStatus(freezeStatus)
                .automaticAssociation(tokenRelation.automaticAssociation())
                .build();
        ret.add(tokenRelationship);
    }

    /**
     * Returns the summary of an account's staking info, given the account, the staking info store, and some
     * information about the network.
     *
     * @param numStoredPeriods the number of periods for which the rewards are stored
     * @param stakePeriodMins the duration of a stake period
     * @param areRewardsActive whether the rewards are active
     * @param account the account
     * @param stakingInfoStore the staking info store
     * @param estimatedConsensusNow the estimated consensus time
     * @return the summary of the account's staking info
     */
    static StakingInfo summarizeStakingInfo(
            final int numStoredPeriods,
            final long stakePeriodMins,
            final boolean areRewardsActive,
            @NonNull final Account account,
            @NonNull final ReadableStakingInfoStore stakingInfoStore,
            @NonNull final Instant estimatedConsensusNow) {
        requireNonNull(account);
        requireNonNull(stakingInfoStore);
        requireNonNull(estimatedConsensusNow);

        final var stakingInfo =
                StakingInfo.newBuilder().declineReward(account.declineReward()).stakedToMe(account.stakedToMe());
        if (account.hasStakedNodeId() && account.stakedNodeIdOrThrow() != SENTINEL_NODE_ID) {
            stakingInfo.stakedNodeId(account.stakedNodeIdOrThrow());
            addNodeStakeMeta(
                    numStoredPeriods,
                    stakePeriodMins,
                    areRewardsActive,
                    account,
                    stakingInfoStore,
                    stakingInfo,
                    estimatedConsensusNow);
        } else if (account.hasStakedAccountId() && account.stakedAccountId() != null) {
            stakingInfo.stakedAccountId(account.stakedAccountIdOrThrow());
        }
        return stakingInfo.build();
    }

    /**
     * Adds the node stake meta to the given staking info builder, given the account
     * and some information about the network.
     *
     * @param numStoredPeriods the number of periods for which the rewards are stored
     * @param stakePeriodMins the duration of a stake period
     * @param areRewardsActive whether the rewards are active
     * @param account the account
     * @param readableStakingInfoStore the readable staking info store
     * @param stakingInfo the staking info builder
     */
    static void addNodeStakeMeta(
            final int numStoredPeriods,
            final long stakePeriodMins,
            final boolean areRewardsActive,
            @NonNull final Account account,
            @NonNull final ReadableStakingInfoStore readableStakingInfoStore,
            @NonNull final StakingInfo.Builder stakingInfo,
            @NonNull final Instant estimatedConsensusNow) {
        stakingInfo
                .stakePeriodStart(Timestamp.newBuilder()
                        .seconds(epochSecondAtStartOfPeriod(account.stakePeriodStart(), stakePeriodMins)))
                .pendingReward(estimatePendingReward(
                        numStoredPeriods,
                        stakePeriodMins,
                        areRewardsActive,
                        account,
                        readableStakingInfoStore,
                        estimatedConsensusNow));
    }
}
