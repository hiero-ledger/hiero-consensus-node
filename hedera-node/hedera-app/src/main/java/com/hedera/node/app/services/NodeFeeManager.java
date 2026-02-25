// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static com.hedera.node.app.services.NodeFeeManager.LastNodeFeesPaymentTime.CURRENT_PERIOD;
import static com.hedera.node.app.services.NodeFeeManager.LastNodeFeesPaymentTime.PREVIOUS_PERIOD;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
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
import com.hedera.node.app.spi.fees.NodeFeeAccumulator;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Manages the NodePayments singleton.
 * Distributes accumulated fees from the fee collection account (0.0.802) at the start of each staking period.
 * <p>
 * Per HIP-1259, all transaction fees are accumulated in the fee collection account during the staking period.
 * At the start of each staking period,
 * <ol>
 *     <li>Pays node fees from the NodePayments map to each node's account</li>
 *     <li>Splits the remaining balance among 0.0.98, 0.0.800, and 0.0.801 if 0.0.801 has minimum required balance.
 *      If not, transfers all the balance to 0.0.801</li>
 *     <li>Resets the node payments singleton</li>
 * </ol>
 */
@Singleton
public class NodeFeeManager implements NodeFeeAccumulator {
    private static final Logger log = LogManager.getLogger(NodeFeeManager.class);

    private final EntityIdFactory entityIdFactory;
    private final ConfigProvider configProvider;

    // The amount of fees to pay to each node. This is updated in-memory each transaction
    // and will be written back to state at the end of every block
    private final Map<AccountID, Long> nodeFees = new LinkedHashMap<>();

    /**
     * Constructs an {@link NodeFeeManager} instance.
     *
     * @param configProvider the configuration provider
     * @param entityIdFactory the entity ID factory
     */
    @Inject
    public NodeFeeManager(
            @NonNull final ConfigProvider configProvider, @NonNull final EntityIdFactory entityIdFactory) {
        this.configProvider = configProvider;
        this.entityIdFactory = entityIdFactory;
    }

    /**
     * Called at the start of each block to load node payments from state into memory.
     * The in-memory map is cleared and then populated from state.
     *
     * @param state the state
     */
    public void onOpenBlock(@NonNull final State state) {
        if (configProvider.getConfiguration().getConfigData(NodesConfig.class).feeCollectionAccountEnabled()) {
            resetNodeFees();
            final var nodePaymentsState = requireNonNull(
                    state.getReadableStates(TokenService.NAME).<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID));
            final var nodePayments =
                    Optional.ofNullable(nodePaymentsState.get()).orElse(NodePayments.DEFAULT);
            nodePayments.payments().forEach(pair -> nodeFees.put(pair.nodeAccountId(), pair.fees()));
            log.debug("Loaded node payments from state: {}", nodePayments);
        }
    }

    /**
     * Called at the end of each block to write accumulated node fees back to state.
     * We clear the in-memory map after writing to state.
     *
     * @param state the state
     */
    public void onCloseBlock(@NonNull final State state) {
        if (configProvider.getConfiguration().getConfigData(NodesConfig.class).feeCollectionAccountEnabled()) {
            updateNodePaymentsState(state);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Accumulates the in-memory node fees for a transaction. This is called for each transaction
     * to accumulate fees without writing to state.
     */
    @Override
    public void accumulate(AccountID nodeAccountId, long fees) {
        nodeFees.merge(nodeAccountId, fees, Long::sum);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Dissipates the in-memory node fees for a refund. This is called when fees that were previously
     * accumulated need to be refunded (e.g., when {@code zeroHapiFees} is enabled for successful
     * Ethereum transactions).
     * <p>
     * If the dissipating results in a negative balance for the node, this method clamps the
     * value to zero and logs a warning.
     */
    @Override
    public void dissipate(AccountID nodeAccountId, long fees) {
        nodeFees.computeIfPresent(nodeAccountId, (id, currentFees) -> {
            final long newFees = currentFees - fees;
            if (newFees < 0) {
                log.warn(
                        "Dissipating {} fees for node {} exceeds accumulated balance (current: {}), clamping to zero",
                        fees,
                        nodeAccountId,
                        currentFees);
            }
            return newFees > 0 ? newFees : null; // Remove entry if zero or negative
        });
    }

    /**
     * Resets the in-memory node fees map.
     */
    public void resetNodeFees() {
        nodeFees.clear();
    }

    /**
     * The possible times at which the last time node fees were distributed
     */
    enum LastNodeFeesPaymentTime {
        /**
         * Node fees have never been distributed. In the genesis edge case, we don't need to distribute fees.
         */
        NEVER,
        /**
         * The last time node fees were distributed was in the previous staking period.
         */
        PREVIOUS_PERIOD,
        /**
         * The last time node fees were distributed was in the current staking period.
         */
        CURRENT_PERIOD,
    }

    /**
     * Checks if the last time node fees were distributed was a different staking period.
     *
     * @param state the state
     * @param now the current time
     * @return whether the last time node fees were distributed was a different staking period
     */
    private LastNodeFeesPaymentTime classifyLastNodeFeesPaymentTime(
            @NonNull final State state, @NonNull final Instant now) {
        final var nodeFeePaymentsStore = new ReadableNodePaymentsStoreImpl(state.getReadableStates(TokenService.NAME));
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
    public boolean distributeFees(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final SystemTransactions systemTransactions) {
        requireNonNull(state);
        requireNonNull(now);
        requireNonNull(systemTransactions);

        final var nodesConfig = configProvider.getConfiguration().getConfigData(NodesConfig.class);
        // Do nothing if fee collection account is disabled
        if (!nodesConfig.feeCollectionAccountEnabled()) {
            return false;
        }

        final var accountsConfig = configProvider.getConfiguration().getConfigData(AccountsConfig.class);
        final var ledgerConfig = configProvider.getConfiguration().getConfigData(LedgerConfig.class);
        final var stakingConfig = configProvider.getConfiguration().getConfigData(StakingConfig.class);

        final var lastNodeFeesPaymentTime = classifyLastNodeFeesPaymentTime(state, now);
        // If we're in the same staking period as the last time node fees were paid, we don't
        // need to do anything
        if (lastNodeFeesPaymentTime == LastNodeFeesPaymentTime.CURRENT_PERIOD) {
            return false;
        }

        final var writableTokenStates = state.getWritableStates(TokenService.NAME);
        final var entityIdStore = new WritableEntityIdStoreImpl(state.getWritableStates(EntityIdService.NAME));
        final var nodePaymentsStore = new WritableNodePaymentsStore(writableTokenStates);
        if (lastNodeFeesPaymentTime == LastNodeFeesPaymentTime.PREVIOUS_PERIOD) {
            log.info("Considering distributing node fees for staking period @ {}", asTimestamp(now));
            // commit the node fees accumulated in the previous period from nodeFees to nodePaymentsStore
            updateNodePaymentsState(state);

            final var accountStore = new WritableAccountStore(writableTokenStates, entityIdStore);
            final var feeCollectionAccountId = entityIdFactory.newAccountId(accountsConfig.feeCollectionAccount());
            final var feeCollectionAccount = requireNonNull(accountStore.getAccountById(feeCollectionAccountId));
            final long feeCollectionBalance = feeCollectionAccount.tinybarBalance();

            final var transferAmounts = new ArrayList<AccountAmount>();
            long totalNodeFees = 0L;

            // Pay node fees to each node's account
            for (final var payment : requireNonNull(nodePaymentsStore.get()).payments()) {
                final var nodeAccount = accountStore.getAccountById(payment.nodeAccountId());
                final var nodeFee = payment.fees();
                // If the node's account cannot accept fees (deleted or doesn't exist), they are forfeit
                if (nodeAccount != null && !nodeAccount.deleted()) {
                    if (nodeFee > 0) {
                        transferAmounts.add(AccountAmount.newBuilder()
                                .accountID(payment.nodeAccountId())
                                .amount(nodeFee)
                                .build());
                        totalNodeFees += nodeFee;
                        log.info("Node account {} will receive {} tinybars", payment.nodeAccountId(), nodeFee);
                    }
                } else {
                    log.info(
                            "Node account {} is deleted or doesn't exist, forfeiting {} tinybars",
                            payment.nodeAccountId(),
                            nodeFee);
                }
            }

            // This should never happen
            if (totalNodeFees > feeCollectionBalance) {
                throw new IllegalStateException(ALERT_MESSAGE + "Total node fees to be distributed" + totalNodeFees
                        + " exceeds fee collection balance " + feeCollectionBalance);
            }
            final long networkServiceFees = feeCollectionBalance - totalNodeFees;

            // Distribute network/service fees to 0.0.98, 0.0.800, and 0.0.801
            final var fundingAccountId = entityIdFactory.newAccountId(ledgerConfig.fundingAccount());
            final var stakingRewardAccountId = entityIdFactory.newAccountId(accountsConfig.stakingRewardAccount());
            final var nodeRewardAccountId = entityIdFactory.newAccountId(accountsConfig.nodeRewardAccount());

            if (networkServiceFees > 0) {
                updateNetworkAndServiceTransferAmounts(
                        networkServiceFees,
                        fundingAccountId,
                        stakingRewardAccountId,
                        nodeRewardAccountId,
                        accountStore,
                        transferAmounts,
                        nodesConfig,
                        stakingConfig);
            }

            // Add the debit from fee collection account
            if (!transferAmounts.isEmpty()) {
                // To avoid any rounding error, so we need to sum up the total distributed amount
                // instead of just using the feeCollectionBalance
                final long totalDistributed = transferAmounts.stream()
                        .mapToLong(AccountAmount::amount)
                        .sum();
                transferAmounts.add(AccountAmount.newBuilder()
                        .accountID(feeCollectionAccountId)
                        .amount(-totalDistributed)
                        .build());
                log.info(
                        "Distributing {} tinybars from fee collection account: {} as node fees, {} to network/service accounts",
                        feeCollectionBalance,
                        totalNodeFees,
                        networkServiceFees);
            }

            systemTransactions.dispatchNodePayments(
                    state,
                    now,
                    TransferList.newBuilder().accountAmounts(transferAmounts).build());
        }
        // Even when the lastFeeDistributionTime=NEVER in genesis case we should reset the node payments state.
        // So, we count this time for next distribution.
        nodePaymentsStore.resetForNewStakingPeriod(asTimestamp(now));
        resetNodeFees();
        ((CommittableWritableStates) writableTokenStates).commit();
        return true;
    }

    /**
     * Distributes network/service fees to 0.0.98, 0.0.800, and 0.0.801 if 0.0.801 is above configured minimum balance.
     * Otherwise, it routes all fees to 0.0.801.
     */
    private void updateNetworkAndServiceTransferAmounts(
            final long amount,
            @NonNull final AccountID fundingAccountId,
            @NonNull final AccountID stakingRewardAccountId,
            @NonNull final AccountID nodeRewardAccountId,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ArrayList<AccountAmount> transferAmounts,
            @NonNull final NodesConfig nodesConfig,
            @NonNull final StakingConfig stakingConfig) {
        long balance = amount;

        final var nodeRewardAccount = requireNonNull(accountStore.getAccountById(nodeRewardAccountId));
        final boolean preservingRewardBalance =
                nodesConfig.nodeRewardsEnabled() && nodesConfig.preserveMinNodeRewardBalance();

        // If 0.0.801 is low, route fees fully there until it reaches the configured minimum
        if (preservingRewardBalance && nodeRewardAccount.tinybarBalance() <= nodesConfig.minNodeRewardBalance()) {
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
     * Updates the node payments state with the accumulated in-memory fees.
     * * This is called at the end of each block and also before distributing fees.
     *
     * @param state the state to update
     */
    private void updateNodePaymentsState(@NonNull final State state) {
        final var writableTokenState = state.getWritableStates(TokenService.NAME);
        final var nodePaymentsState = writableTokenState.<NodePayments>getSingleton(NODE_PAYMENTS_STATE_ID);
        final var currentPayments = requireNonNull(nodePaymentsState.get());

        // Build updated payments list from in-memory nodeFees map
        final var updatedPayments = nodeFees.entrySet().stream()
                .map(entry -> NodePayment.newBuilder()
                        .nodeAccountId(entry.getKey())
                        .fees(entry.getValue())
                        .build())
                .sorted((a, b) -> Long.compare(
                        a.nodeAccountId().accountNum(), b.nodeAccountId().accountNum()))
                .toList();

        // We don't update the lastNodeFeeDistributionTime because it is updated only once we distribute fees
        nodePaymentsState.put(
                currentPayments.copyBuilder().payments(updatedPayments).build());
        ((CommittableWritableStates) writableTokenState).commit();
        log.debug("Committed node payments state with {}", updatedPayments);

        // Clear the in-memory node fees map
        resetNodeFees();
    }
}
