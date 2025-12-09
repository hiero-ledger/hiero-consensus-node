// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static com.hedera.node.app.services.NodeFeeDistributor.LastNodeFeesPaymentTime.CURRENT_PERIOD;
import static com.hedera.node.app.services.NodeFeeDistributor.LastNodeFeesPaymentTime.PREVIOUS_PERIOD;
import static com.hedera.node.app.workflows.handle.steps.StakePeriodChanges.isNextStakingPeriod;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableNodePaymentsStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableNodePaymentsStore;
import com.hedera.node.app.workflows.handle.record.SystemTransactions;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.StakingConfig;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
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
public class NodeFeeDistributor {
    private static final Logger log = LogManager.getLogger(NodeFeeDistributor.class);

    private final AccountsConfig accountsConfig;
    private final LedgerConfig ledgerConfig;
    private final StakingConfig stakingConfig;
    private final NodesConfig nodesConfig;
    private final EntityIdFactory entityIdFactory;
    private final ConfigProvider configProvider;

    // The amount of fees to pay to each node. This is updated from state at the start of every round
    // and will be written back to state at the end of every block
    private final SortedMap<Long, Long> nodeFees = new TreeMap<>();

    /**
     * Constructs an {@link NodeFeeDistributor} instance.
     *
     * @param configProvider the configuration provider
     * @param entityIdFactory the entity ID factory
     */
    @Inject
    public NodeFeeDistributor(
            @NonNull final ConfigProvider configProvider,
            @NonNull final EntityIdFactory entityIdFactory) {
        final var config = configProvider.getConfiguration();
        this.configProvider = configProvider;
        this.accountsConfig = config.getConfigData(AccountsConfig.class);
        this.ledgerConfig = config.getConfigData(LedgerConfig.class);
        this.stakingConfig = config.getConfigData(StakingConfig.class);
        this.nodesConfig = config.getConfigData(NodesConfig.class);
        this.entityIdFactory = entityIdFactory;
    }

    public void onOpenBlock(@NonNull final State state) {
        // read the node payment info from state at start of every block. So, we can commit the accumulated changes
        // at end of every block
        if (configProvider.getConfiguration().getConfigData(NodesConfig.class).feeCollectionAccountEnabled()) {
            nodeFees.clear();
            final var nodePayments = nodePaymentsFrom(state);
            nodePayments.payments().forEach(pair -> nodeFees.put(pair.accountNumber(), pair.fees()));
        }
    }

    /**
     * Updates node rewards state at the end of a block given the collected node fees.
     *
     * @param state the state
     */
    public void onCloseBlock(@NonNull final State state) {
        if (configProvider.getConfiguration().getConfigData(NodesConfig.class).feeCollectionAccountEnabled()) {
            updateNodePaymentsState(state);
        }
    }

    public void updateFeesEachTransaction(long nodeAccountNumber, long fees) {
        nodeFees.merge(nodeAccountNumber, fees, Long::sum);
    }

    public void resetNodeFees() {
        nodeFees.clear();
    }

    /**
     * The possible times at which the last time node rewards were paid.
     */
    enum LastNodeFeesPaymentTime {
        /**
         * Node fees have never been paid. In the genesis edge case, we don't need to pay rewards.
         */
        NEVER,
        /**
         * The last time node fees were paid was in the previous staking period.
         */
        PREVIOUS_PERIOD,
        /**
         * The last time node fees were paid was in the current staking period.
         */
        CURRENT_PERIOD,
    }

    /**
     * Checks if the last time node rewards were paid was a different staking period.
     *
     * @param state the state
     * @param now the current time
     * @return whether the last time node rewards were paid was a different staking period
     */
    private LastNodeFeesPaymentTime classifyLastNodeFeesPaymentTime(@NonNull final State state, @NonNull final Instant now) {
        final var nodeFeePaymentsStore =
                new ReadableNodePaymentsStoreImpl(state.getReadableStates(TokenService.NAME));
        final var lastPaidTime = nodeFeePaymentsStore.get().lastNodeFeeDistributionTime();
        if (lastPaidTime == null) {
            return LastNodeFeesPaymentTime.NEVER;
        }
        final long stakePeriodMins = configProvider
                .getConfiguration()
                .getConfigData(StakingConfig.class)
                .periodMins();
        final boolean isNextPeriod = isNextStakingPeriod(now, asInstant(lastPaidTime), stakePeriodMins);
        return isNextPeriod ? PREVIOUS_PERIOD : CURRENT_PERIOD;
    }

    /**
     * Distributes accumulated fees from the fee collection account to node accounts and system accounts.
     * <p>
     * This method should be called at the start of each new staking period, before any other operations.
     *
     * @param state the savepoint stack for the current transaction
     * @return the stream builder for the synthetic fee distribution transaction, or null if no fees to distribute
     */
    public boolean distributeFees(@NonNull final State state,
                                  @NonNull final Instant now,
                                  final SystemTransactions systemTransactions) {
        requireNonNull(state);
        requireNonNull(now);
        requireNonNull(systemTransactions);

        if (!nodesConfig.feeCollectionAccountEnabled()) {
            return false;
        }
        final var lastNodeFeesPaymentTime = classifyLastNodeFeesPaymentTime(state, now);
        // If we're in the same staking period as the last time node fees were paid, we don't
        // need to do anything
        if (lastNodeFeesPaymentTime == LastNodeFeesPaymentTime.CURRENT_PERIOD) {
            return false;
        }

        final var writableTokenStates = state.getWritableStates(TokenService.NAME);
        final var entityIdStore = new WritableEntityIdStoreImpl(state.getWritableStates(EntityIdService.NAME));
        final var nodePaymentsStore = new WritableNodePaymentsStore(writableTokenStates);
        final var accountStore = new WritableAccountStore(writableTokenStates, entityIdStore);
        if (lastNodeFeesPaymentTime == LastNodeFeesPaymentTime.PREVIOUS_PERIOD) {
            log.info("Distributing accumulated fees for the just-finished staking period @ {}", asTimestamp(now));

            final var feeCollectionAccountId = entityIdFactory.newAccountId(accountsConfig.feeCollectionAccount());
            final var feeCollectionAccount = requireNonNull(accountStore.getAccountById(feeCollectionAccountId));
            final long feeCollectionBalance = feeCollectionAccount.tinybarBalance();

            final var transferAmounts = new ArrayList<AccountAmount>();
            long totalNodeFees = 0L;

            // Pay node fees to each node's account
            for (final var payment : requireNonNull(nodePaymentsStore.get()).payments()) {
                final var nodeAccountId = entityIdFactory.newAccountId(payment.accountNumber());
                final var nodeAccount = accountStore.getAccountById(nodeAccountId);
                final var nodeFee = payment.fees();
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
                final long totalDistributed = transferAmounts.stream().mapToLong(AccountAmount::amount).sum();
                transferAmounts.add(AccountAmount.newBuilder()
                        .accountID(feeCollectionAccountId)
                        .amount(-totalDistributed)
                        .build());
            }

            log.info(
                    "Distributing {} tinybars from fee collection account: {} to nodes, {} to network/service accounts",
                    feeCollectionBalance,
                    totalNodeFees,
                    networkServiceFees);

            systemTransactions.dispatchNodePayments(state, now, TransferList.newBuilder().accountAmounts(transferAmounts).build());
        }
        nodePaymentsStore.resetForNewStakingPeriod(asTimestamp(now));
        resetNodeFees();
        return true;
    }


    /**
     * Updates the node reward state in the given state. This method will be called at the end of every block.
     * <p>
     * This method updates the number of rounds in the staking period and the number of missed judge rounds for
     * each node.
     *
     * @param state the state to update
     */
    private void updateNodePaymentsState(@NonNull final State state) {
        final var writableTokenState = state.getWritableStates(TokenService.NAME);
        final var nodePaymentsState = writableTokenState.<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID);
        final var nodePaymentsBuilder = NodePayments.newBuilder();

        final var existingNodePaymentsMap = requireNonNull(nodePaymentsState.get()).payments().stream()
                .collect(Collectors.toMap(NodePayment::accountNumber, NodePayment::fees));
        // Add the fees collected in the block to the existing node payments map. If new nodes have joined, they will
        // have an entry in the nodeFees map but not in the existingNodePaymentsMap. In that case, we add them to the
        // existingNodePaymentsMap with a fee of 0.
        for (final var entry : existingNodePaymentsMap.entrySet()) {
            nodeFees.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        // compute node payments from nodeFees
        nodeFees.forEach((accountNumber, fees) -> nodePaymentsBuilder.payments(NodePayment.newBuilder()
                .accountNumber(accountNumber)
                .fees(fees)
                .build()));
        nodePaymentsState.put(nodePaymentsBuilder.build());
        ((CommittableWritableStates) writableTokenState).commit();
        log.info("Updated node payments state with {} entries", nodePaymentsBuilder.payments().size());
    }


    /**
     * Gets the node reward info state from the given state.
     *
     * @param state the state
     * @return the node reward info state
     */
    private @NonNull NodePayments nodePaymentsFrom(@NonNull final State state) {
        final var nodePayments =
                state.getReadableStates(TokenService.NAME).<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID);
        return requireNonNull(nodePayments.get());
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
}
