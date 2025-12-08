// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.signedTxWith;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNodePaymentsStore;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.service.token.records.TokenContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Distributes accumulated fees from the fee collection account (0.0.802) at the end of each staking period.
 * <p>
 * Per HIP-1259, all transaction fees are accumulated in the fee collection account during the staking period.
 * At the end of each staking period, this class:
 * <ol>
 *     <li>Pays node fees from the NodePayments map to each node's account</li>
 *     <li>Splits the remaining balance among 0.0.98, 0.0.800, and 0.0.801 per current rules</li>
 *     <li>Resets NodePayments to an empty map</li>
 * </ol>
 */
@Singleton
public class FeeDistributor {
    private static final Logger log = LogManager.getLogger(FeeDistributor.class);

    public static final String FEE_DISTRIBUTION_MEMO = "Synthetic end of staking period fee distribution";

    private final AccountsConfig accountsConfig;
    private final LedgerConfig ledgerConfig;
    private final StakingConfig stakingConfig;
    private final NodesConfig nodesConfig;
    private final EntityIdFactory entityIdFactory;

    /**
     * Constructs an {@link FeeDistributor} instance.
     *
     * @param configProvider the configuration provider
     * @param entityIdFactory the entity ID factory
     */
    @Inject
    public FeeDistributor(
            @NonNull final ConfigProvider configProvider,
            @NonNull final EntityIdFactory entityIdFactory) {
        final var config = configProvider.getConfiguration();
        this.accountsConfig = config.getConfigData(AccountsConfig.class);
        this.ledgerConfig = config.getConfigData(LedgerConfig.class);
        this.stakingConfig = config.getConfigData(StakingConfig.class);
        this.nodesConfig = config.getConfigData(NodesConfig.class);
        this.entityIdFactory = entityIdFactory;
    }

    /**
     * Distributes accumulated fees from the fee collection account to node accounts and system accounts.
     * <p>
     * This method should be called at the start of each new staking period, before any other operations.
     *
     * @param state the savepoint stack for the current transaction
     * @param context the token context for the current transaction
     * @param exchangeRateSet the current exchange rate set
     * @return the stream builder for the synthetic fee distribution transaction, or null if no fees to distribute
     */
    @Nullable
    public StreamBuilder distributeFees(
            @NonNull final State state,
            @NonNull final TokenContext context,
            @NonNull final ExchangeRateSet exchangeRateSet) {
        requireNonNull(state);
        requireNonNull(context);
        requireNonNull(exchangeRateSet);
        log.info("Distributing accumulated fees for the just-finished staking period @ {}", context.consensusTime());

        final var accountStore = context.writableStore(WritableAccountStore.class);
        final var nodePaymentsStore = context.writableStore(WritableNodePaymentsStore.class);

        final var feeCollectionAccountId = entityIdFactory.newAccountId(accountsConfig.feeCollectionAccount());
        final var feeCollectionAccount = requireNonNull(accountStore.getAccountById(feeCollectionAccountId));
        final long feeCollectionBalance = feeCollectionAccount.tinybarBalance();

        if (feeCollectionBalance == 0) {
            log.info("Fee collection account has zero balance, skipping fee distribution");
            nodePaymentsStore.resetForNewStakingPeriod();
            return null;
        }

        final var nodePayments = requireNonNull(nodePaymentsStore.get());

        final var transferAmounts = new ArrayList<AccountAmount>();
        long totalNodeFees = 0L;

        // Pay node fees to each node's account
        for (final var entry : nodePayments.payments().entrySet()) {
            final var nodeAccountId = entityIdFactory.newAccountId(entry.getKey());
            final var nodeAccount = accountStore.getAccountById(nodeAccountId);
            final var nodeFee = entry.getValue().fees();
            // If the node's account cannot accept fees (deleted or doesn't exist), they are forfeit
            if (nodeAccount != null && !nodeAccount.deleted()) {
                if (nodeFee > 0) {
                    transferAmounts.add(AccountAmount.newBuilder()
                            .accountID(nodeAccountId)
                            .amount(nodeFee)
                            .build());
                    totalNodeFees += nodeFee;
                    log.info("Node account {} will receive {} tinybars", nodeAccountId, nodeFee);
                }
            } else {
                log.info("Node account {} is deleted or doesn't exist, forfeiting {} tinybars", nodeAccountId, nodeFee);
            }
        }

        // Calculate network/service fees (remaining balance after node fees)
        final long networkServiceFees = feeCollectionBalance - totalNodeFees;

        // Distribute network/service fees to 0.0.98, 0.0.800, and 0.0.801
        final var fundingAccountId = entityIdFactory.newAccountId(ledgerConfig.fundingAccount());
        final var stakingRewardAccountId = entityIdFactory.newAccountId(accountsConfig.stakingRewardAccount());
        final var nodeRewardAccountId = entityIdFactory.newAccountId(accountsConfig.nodeRewardAccount());

        if (networkServiceFees > 0) {
            distributeNetworkServiceFees(
                    networkServiceFees,
                    fundingAccountId,
                    stakingRewardAccountId,
                    nodeRewardAccountId,
                    accountStore,
                    transferAmounts);
        }

        // Add the debit from fee collection account
        if (!transferAmounts.isEmpty()) {
            final long totalDistributed =
                    transferAmounts.stream().mapToLong(AccountAmount::amount).sum();
            transferAmounts.add(AccountAmount.newBuilder()
                    .accountID(feeCollectionAccountId)
                    .amount(-totalDistributed)
                    .build());
        }

        // Reset the NodePayments map for the new staking period
        nodePaymentsStore.resetForNewStakingPeriod();
        if (transferAmounts.isEmpty()) {
            log.info("No fees to distribute");
            return null;
        }

        // Apply the balance changes directly to the accounts
        applyBalanceChanges(transferAmounts, accountStore);

        // Create the synthetic fee distribution transaction
        final var transferBody = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(transferAmounts)
                        .build())
                .build();
        final var syntheticFeeDistributionTxn = TransactionBody.newBuilder()
                .memo(FEE_DISTRIBUTION_MEMO)
                .cryptoTransfer(transferBody)
                .build();

        log.info(
                "Distributing {} tinybars from fee collection account: {} to nodes, {} to network/service accounts",
                feeCollectionBalance,
                totalNodeFees,
                networkServiceFees);

        // Create a preceding child record for the fee distribution
        return context.addPrecedingChildRecordBuilder(CryptoTransferStreamBuilder.class, CRYPTO_TRANSFER)
                .signedTx(signedTxWith(syntheticFeeDistributionTxn))
                .memo(FEE_DISTRIBUTION_MEMO)
                .exchangeRate(exchangeRateSet)
                .status(SUCCESS);
    }

    /**
     * Distributes network/service fees to 0.0.98, 0.0.800, and 0.0.801 per current rules.
     */
    private void distributeNetworkServiceFees(
            final long amount,
            @NonNull final AccountID fundingAccountId,
            @NonNull final AccountID stakingRewardAccountId,
            @NonNull final AccountID nodeRewardAccountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ArrayList<AccountAmount> transferAmounts) {
        long balance = amount;

        final var nodeRewardAccount = accountStore.getAccountById(nodeRewardAccountId);
        final boolean preservingRewardBalance =
                nodesConfig.nodeRewardsEnabled() && nodesConfig.preserveMinNodeRewardBalance();

        // If 0.0.801 is low, route fees fully there until it reaches the configured minimum
        if (preservingRewardBalance
                && nodeRewardAccount != null
                && nodeRewardAccount.tinybarBalance() <= nodesConfig.minNodeRewardBalance()) {
            // Route all fees to node reward account
            transferAmounts.add(AccountAmount.newBuilder()
                    .accountID(nodeRewardAccountId)
                    .amount(balance)
                    .build());
            log.info("Routing all {} tinybars to node reward account (below minimum balance)", balance);
            return;
        }
        // Normal distribution: split among 0.0.98, 0.0.800, and 0.0.801
        final long nodeReward = (stakingConfig.feesNodeRewardPercentage() * amount) / 100;
        balance -= nodeReward;
        if (nodeReward > 0) {
            transferAmounts.add(AccountAmount.newBuilder()
                    .accountID(nodeRewardAccountId)
                    .amount(nodeReward)
                    .build());
        }
        final long stakingReward = (stakingConfig.feesStakingRewardPercentage() * amount) / 100;
        balance -= stakingReward;
        if (stakingReward > 0) {
            transferAmounts.add(AccountAmount.newBuilder()
                    .accountID(stakingRewardAccountId)
                    .amount(stakingReward)
                    .build());
        }
        // Whatever is left over goes to the funding account
        if (balance > 0) {
            transferAmounts.add(AccountAmount.newBuilder()
                    .accountID(fundingAccountId)
                    .amount(balance)
                    .build());
        }
    }

    /**
     * Applies the balance changes to the accounts.
     */
    private void applyBalanceChanges(
            @NonNull final ArrayList<AccountAmount> transferAmounts, @NonNull final WritableAccountStore accountStore) {
        for (final var transfer : transferAmounts) {
            final var accountId = transfer.accountID();
            final var account = accountStore.getAccountById(accountId);
            if (account != null) {
                final var newBalance = account.tinybarBalance() + transfer.amount();
                accountStore.put(
                        account.copyBuilder().tinybarBalance(newBalance).build());
            }
        }
    }
}
