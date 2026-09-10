// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.config.data.ClprConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Static utilities for connector slashing, geometric penalty escalation, and endpoint reimbursement.
 */
public final class ClprSlashingUtils {

    private ClprSlashingUtils() {}

    /**
     * Result of applying a slash to a connector.
     *
     * @param updatedConnector the connector after slashing, or null if banned and removed
     * @param penaltyAmount the actual tinybars slashed
     * @param banned whether the connector was banned (slash threshold exceeded)
     */
    public record SlashResult(@Nullable ClprConnector updatedConnector, long penaltyAmount, boolean banned) {}

    /**
     * Computes the geometric penalty: {@code basePenalty * multiplier^slashCount},
     * capped at the connector's remaining {@code lockedStake}.
     *
     * @param basePenalty the base penalty in tinybars
     * @param multiplier the geometric multiplier
     * @param slashCount the connector's current slash count
     * @param lockedStake the connector's remaining locked stake
     * @return the penalty amount, never exceeding lockedStake
     */
    public static long computePenalty(
            final long basePenalty, final int multiplier, final int slashCount, final long lockedStake) {
        if (lockedStake <= 0 || basePenalty <= 0) {
            return 0;
        }
        long penalty = basePenalty;
        for (int i = 0; i < slashCount; i++) {
            penalty *= multiplier;
            if (penalty >= lockedStake) {
                return lockedStake;
            }
        }
        return Math.min(penalty, lockedStake);
    }

    /**
     * Applies a slash to the given connector. Increments {@code slash_count}, deducts the
     * geometric penalty from {@code locked_stake}, and checks the ban threshold.
     *
     * <p>If the new slash count reaches the ban threshold, the connector is removed from
     * state and its entire remaining stake is forfeited as the penalty.
     *
     * @param connector the connector to slash
     * @param config the CLPR configuration
     * @param connectorStore the writable connector store
     * @return the slash result
     */
    @SuppressWarnings("exports")
    @NonNull
    public static SlashResult applySlash(
            @NonNull final ClprConnector connector,
            @NonNull final ClprConfig config,
            @NonNull final WritableConnectorStore connectorStore) {
        requireNonNull(connector);
        requireNonNull(config);
        requireNonNull(connectorStore);

        final var newSlashCount = connector.slashCount() + 1;

        if (newSlashCount >= config.slashBanThreshold()) {
            // Ban: forfeit all remaining stake and remove connector
            final var forfeited = connector.lockedStake();
            connectorStore.remove(new ClprConnectorKey(connector.channelId(), connector.connectorId()));
            return new SlashResult(null, forfeited, true);
        }

        final var penalty = computePenalty(
                config.slashBasePenalty(), config.slashMultiplier(), connector.slashCount(), connector.lockedStake());
        final var newLockedStake = connector.lockedStake() - penalty;

        final var updated = connector
                .copyBuilder()
                .lockedStake(newLockedStake)
                .slashCount(newSlashCount)
                .build();
        connectorStore.put(updated);

        return new SlashResult(updated, penalty, false);
    }

    /**
     * Transfers slashed tinybars from the CLPR staking account to the endpoint (payer).
     *
     * <p>The payout is capped at the staking account's current balance (and skipped entirely if the
     * account does not exist). A slash reduces the connector's {@code locked_stake} <em>state field</em>
     * but moves no hbar; this reimbursement is the only hbar leg. If the reimbursement were allowed to
     * exceed what the staking account actually escrows — e.g. a well-known connector that never posted
     * stake, so nothing backs its {@code locked_stake} — the endpoint would be credited hbar with no
     * surviving debit, leaving a non-zero net hbar change that the record finalizer rejects with
     * {@code FAIL_INVALID} (a CATASTROPHIC failure of the whole bundle). Capping to the backed balance
     * keeps the credit and the debit equal, so hbar is always conserved.
     *
     * @param amount the desired reimbursement in tinybars (the slash penalty)
     * @param payerAccountId the endpoint account to reimburse
     * @param config the CLPR configuration
     * @param entityIdFactory factory for creating account IDs
     * @param accountStore read access to resolve the staking account's backing balance
     * @param tokenServiceApi the token service API for transfers
     * @return the amount actually reimbursed (never more than {@code amount}, never more than the
     *     staking account's balance, never negative)
     */
    @SuppressWarnings("exports")
    public static long reimburseEndpoint(
            final long amount,
            @NonNull final AccountID payerAccountId,
            @NonNull final ClprConfig config,
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TokenServiceApi tokenServiceApi) {
        if (amount <= 0) {
            return 0L;
        }
        final var stakingAccountId = entityIdFactory.newAccountId(config.stakingAccount());
        final var stakingAccount = accountStore.getAccountById(stakingAccountId);
        final var backed = stakingAccount == null ? 0L : stakingAccount.tinybarBalance();
        final var reimbursable = Math.min(amount, backed);
        if (reimbursable <= 0) {
            return 0L;
        }
        tokenServiceApi.transferFromTo(stakingAccountId, payerAccountId, reimbursable);
        return reimbursable;
    }
}
