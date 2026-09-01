// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.analyzeStakingAccounts;
import static com.hedera.node.app.service.token.impl.handlers.staking.StakingRewardsHelper.asAccountAmounts;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_LABEL;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.impl.RecordFinalizerBase;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures the dispatch-scoped staking analysis and HBAR record assembly used by the common serial
 * {@code CryptoTransfer} path. State construction and transaction mutation happen outside the measured method.
 */
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SerialCryptoTransferStakingBenchmark extends RecordFinalizerBase {
    private static final long MAX_LEGAL_BALANCE = 5_000_000_000_000_000_000L;
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID RECEIVER_ID =
            AccountID.newBuilder().accountNum(1002).build();
    private static final Account PAYER = Account.newBuilder()
            .accountId(PAYER_ID)
            .tinybarBalance(2_000_000_000L)
            .stakedNodeId(0L)
            .build();
    private static final Account RECEIVER = Account.newBuilder()
            .accountId(RECEIVER_ID)
            .tinybarBalance(1_000_000_000L)
            .stakedNodeId(0L)
            .build();
    private static final WritableEntityCounters NO_OP_COUNTERS = new WritableEntityCounters() {
        @Override
        public long getCounterFor(final EntityType entityType) {
            return 0;
        }

        @Override
        public void decrementEntityTypeCounter(final EntityType entityType) {}

        @Override
        public void incrementEntityTypeCount(final EntityType entityType) {}

        @Override
        public void adjustEntityCount(final EntityType entityType, final long delta) {}
    };

    private WritableAccountStore accountStore;

    @Setup(Level.Invocation)
    public void setUpInvocation() {
        final var accounts = new HashMap<AccountID, Account>();
        accounts.put(PAYER_ID, PAYER);
        accounts.put(RECEIVER_ID, RECEIVER);
        final var accountState = new MapWritableKVState<>(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL, accounts);
        final var aliasState =
                new MapWritableKVState<ProtoBytes, AccountID>(ALIASES_STATE_ID, ALIASES_STATE_LABEL, new HashMap<>());
        accountStore = new WritableAccountStore(
                new MapWritableStates(Map.of(ACCOUNTS_STATE_ID, accountState, ALIASES_STATE_ID, aliasState)),
                NO_OP_COUNTERS);
        accountStore.put(PAYER.copyBuilder()
                .tinybarBalance(PAYER.tinybarBalance() - 1_000L)
                .build());
        accountStore.put(RECEIVER.copyBuilder()
                .tinybarBalance(RECEIVER.tinybarBalance() + 1_000L)
                .build());
    }

    @Benchmark
    public void analyzeAndAssembleHbarChanges(final Blackhole blackhole) {
        try (final var lease = acquireScratch()) {
            final var analysis = analyzeStakingAccounts(
                    accountStore, Set.of(), Set.of(), lease.scratch().stakingScratch());
            final var hbarChanges = hbarChangesFrom(
                    accountStore,
                    MAX_LEGAL_BALANCE,
                    analysis.originalAccounts(),
                    lease.scratch().hbarChanges());
            blackhole.consume(TransferList.newBuilder()
                    .accountAmounts(asAccountAmounts(hbarChanges))
                    .build());
        }
    }

    @Override
    protected boolean systemEntitiesCreated() {
        return false;
    }
}
