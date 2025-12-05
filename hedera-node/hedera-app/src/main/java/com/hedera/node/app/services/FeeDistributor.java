package com.hedera.node.app.services;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.StakingConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;

public class FeeDistributor {
    private final NodesConfig nodesConfig;
    private final StakingConfig stakingConfig;
    private final AccountID fundingAccountID;
    private final AccountID stakingRewardAccountID;

    @Inject
    public FeeDistributor(ConfigProvider configProvider) {
        final var config = configProvider.getConfiguration();
        stakingConfig = config.getConfigData(StakingConfig.class);
        nodesConfig = config.getConfigData(NodesConfig.class);

        // Compute the account ID's for funding (normally 0.0.98) and staking rewards (normally 0.0.800).
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        fundingAccountID = AccountID.newBuilder()
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .accountNum(config.getConfigData(LedgerConfig.class).fundingAccount())
                .build();
        stakingRewardAccountID = AccountID.newBuilder()
                .shardNum(hederaConfig.shard())
                .realmNum(hederaConfig.realm())
                .accountNum(config.getConfigData(AccountsConfig.class).stakingRewardAccount())
                .build();
    }

    private void distributeToNetworkFundingAccounts(final long amount, @Nullable final ObjLongConsumer<AccountID> cb) {
        // We may have a rounding error, so we will first remove the node and staking rewards from the total, and then
        // whatever is left over goes to the funding account.
        long balance = amount;

        final var nodeRewardAccount = lookupAccount("Node rewards", nodeRewardAccountID);
        final boolean preservingRewardBalance =
                nodesConfig.nodeRewardsEnabled() && nodesConfig.preserveMinNodeRewardBalance();
        if (!preservingRewardBalance || nodeRewardAccount.tinybarBalance() > nodesConfig.minNodeRewardBalance()) {
            final long nodeReward = (stakingConfig.feesNodeRewardPercentage() * amount) / 100;
            balance -= nodeReward;
            payNodeRewardAccount(nodeReward);
            if (cb != null) {
                cb.accept(nodeRewardAccountID, nodeReward);
            }

            final long stakingReward = (stakingConfig.feesStakingRewardPercentage() * amount) / 100;
            balance -= stakingReward;
            payStakingRewardAccount(stakingReward);
            if (cb != null) {
                cb.accept(stakingRewardAccountID, stakingReward);
            }

            // Whatever is left over goes to the funding account
            final var fundingAccount = lookupAccount("Funding", fundingAccountID);
            accountStore.put(fundingAccount
                    .copyBuilder()
                    .tinybarBalance(fundingAccount.tinybarBalance() + balance)
                    .build());
            if (cb != null) {
                cb.accept(fundingAccountID, balance);
            }
        } else {
            payNodeRewardAccount(balance);
            if (cb != null) {
                cb.accept(nodeRewardAccountID, balance);
            }
        }
    }

    /**
     * Pays the staking reward account the given amount. If the staking reward account doesn't exist, an exception is
     * thrown. This account *should* have been created at genesis, so it should always exist, even if staking rewards
     * are disabled.
     *
     * @param amount The amount to credit the staking reward account.
     * @throws IllegalStateException if the staking rewards account doesn't exist
     */
    private void payStakingRewardAccount(final long amount) {
        if (amount == 0) return;
        final var stakingAccount = lookupAccount("Staking reward", stakingRewardAccountID);
        accountStore.put(stakingAccount
                .copyBuilder()
                .tinybarBalance(stakingAccount.tinybarBalance() + amount)
                .build());
    }

    /**
     * Retracts the given amount from the node staking account the given amount. If the node reward account doesn't
     * exist, an exception is thrown.
     *
     * @param amount The amount to debit the node staking account.
     * @throws IllegalStateException if the node staking account doesn't exist
     */
    private long retractStakingRewardAccount(final long amount) {
        if (amount == 0) return 0L;
        final var stakingAccount = lookupAccount("Staking reward", stakingRewardAccountID);
        final long balance = stakingAccount.tinybarBalance();
        final long amountToRetract = Math.min(amount, balance);
        accountStore.put(stakingAccount
                .copyBuilder()
                .tinybarBalance(balance - amountToRetract)
                .build());
        return amountToRetract;
    }

    @NonNull
    private Account lookupAccount(String logName, AccountID id) {
        var account = accountStore.get(id);
        if (account == null) {
            throw new IllegalStateException(logName + " account " + id + " does not exist");
        }
        return account;
    }
}
