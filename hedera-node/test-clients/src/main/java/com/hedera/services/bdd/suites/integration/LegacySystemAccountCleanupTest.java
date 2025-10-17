// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.exports.recordstreaming.RecordStreamingUtils.orderedRecordFilesFrom;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.hedera.services.bdd.junit.restart.RestartType.UPGRADE_BOUNDARY;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.hedera.NodeSelector;
import com.hedera.services.bdd.junit.restart.RestartHapiTest;
import com.hedera.services.bdd.junit.restart.SavedStateSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * A test that "picks up" after a simulated restart with legacy system accounts in state, and verifies that they
 * are cleaned up as expected.
 * <p>
 * <i>(FUTURE)</i> Remove this after release 0.66; it is a one-time only validation.
 */
@Order(10)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class LegacySystemAccountCleanupTest implements SavedStateSpec {
    private static final long FIRST_SYSTEM_FILE_ENTITY = 101L;
    private static final long FIRST_BALANCE_TO_SWEEP = 123;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;

    @RestartHapiTest(restartType = UPGRADE_BOUNDARY, savedStateSpec = LegacySystemAccountCleanupTest.class)
    final Stream<DynamicTest> legacyAccountsCleanedUpPostUpgrade() {
        return hapiTest(
                // Send a burst of rounds through
                blockingOrder(IntStream.range(0, 10)
                        .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)))
                        .toArray(SpecOperation[]::new)),
                withOpContext((spec, opLog) -> {
                    spec.embeddedHederaOrThrow().stop();
                    // Ensure the legacy accounts are gone
                    final var accounts = spec.embeddedAccountsOrThrow();
                    for (long i = FIRST_SYSTEM_FILE_ENTITY; i < FIRST_POST_SYSTEM_FILE_ENTITY; i++) {
                        final var id = spec.accountIdFactory().apply(i);
                        final var account = accounts.get(toPbj(id));
                        assertNull(account, "Account #" + i + " should not exist after upgrade");
                    }
                    // Validate the HBAR supply is intact
                    final var map = (MapWritableKVState<AccountID, Account>) accounts;
                    final var totalHbarBalance = map.getBackingStore().values().stream()
                            .mapToLong(Account::tinybarBalance)
                            .sum();
                    assertEquals(50 * ONE_BILLION_HBARS, totalHbarBalance, "Wrong HBAR balance after upgrade");

                    // Check the entity counts singleton is decremented to match
                    final var singleton = spec.embeddedStateOrThrow()
                            .getReadableStates(EntityIdService.NAME)
                            .<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID);
                    final var expectedAccounts = map.getBackingStore().size();
                    final var actualCounts = requireNonNull(singleton.get()).numAccounts();
                    assertEquals(expectedAccounts, actualCounts, "Singleton count mismatch after upgrade");

                    // And validate the resulting record stream
                    assertRecordStreamsAsExpected(spec.recordStreamsLoc(NodeSelector.byNodeId(0L)));
                }));
    }

    private void assertRecordStreamsAsExpected(@NonNull final Path path) {
        try {
            final var loc = path.toAbsolutePath().toString();
            final var lastRecordFile = orderedRecordFilesFrom(loc, f -> true).getLast();
            final var lastSignatureFile = lastRecordFile.replace(".gz", "_sig");
            conditionFuture(() -> new File(lastSignatureFile).exists(), () -> 500L)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .join();
            final var data = STREAM_FILE_ACCESS.readStreamDataFrom(loc, "sidecar");
            final var entries = data.records().stream()
                    .flatMap(r -> r.recordFile().getRecordStreamItemsList().stream())
                    .map(RecordStreamEntry::from)
                    .toList();
            Instant now = null;
            final Set<Instant> timestamps = new HashSet<>();
            final Set<Long> accountNumbersToSweep =
                    new HashSet<>(LongStream.range(FIRST_BALANCE_TO_SWEEP, FIRST_POST_SYSTEM_FILE_ENTITY)
                            .boxed()
                            .toList());
            for (final var entry : entries) {
                final var timestamp = entry.consensusTime();
                if (!timestamps.add(timestamp)) {
                    Assertions.fail("Duplicate timestamp found in record stream: " + timestamp);
                }
                if (now != null && !timestamp.isAfter(now)) {
                    Assertions.fail("Timestamp " + timestamp + " is not after previous timestamp " + now);
                }
                now = timestamp;
                AccountAmount match;
                if (entry.function() == CryptoTransfer
                        && (match = entry.body().getCryptoTransfer().getTransfers().getAccountAmountsList().stream()
                                        .filter(aa ->
                                                isSystemFile(aa.getAccountID().getAccountNum()) && aa.getAmount() < 0L)
                                        .findAny()
                                        .orElse(null))
                                != null) {
                    final var adjusts =
                            entry.body().getCryptoTransfer().getTransfers().getAccountAmountsList();
                    // Sanity check we are exactly sweeping into 0.0.98
                    assertEquals(2, adjusts.size());
                    final var feeCollectorAdjust = adjusts.getLast();
                    assertEquals(98, feeCollectorAdjust.getAccountID().getAccountNum());
                    assertEquals(Math.abs(match.getAmount()), feeCollectorAdjust.getAmount());
                    final long targetNumber = match.getAccountID().getAccountNum();
                    accountNumbersToSweep.remove(targetNumber);
                }
            }
            if (!accountNumbersToSweep.isEmpty()) {
                Assertions.fail("Not all legacy system accounts were swept: " + accountNumbersToSweep);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void accept(@NonNull final FakeState fakeState) {
        final var tokenStates = (MapWritableStates) fakeState.getWritableStates(TokenService.NAME);
        final var accounts =
                (MapWritableKVState<AccountID, Account>) tokenStates.<AccountID, Account>get(ACCOUNTS_STATE_ID);
        final var sampleId = accounts.getBackingStore().keySet().iterator().next();
        final var idFactory = getIdFactoryFor(sampleId);
        final var treasuryId = idFactory.apply(2L);
        final var treasuryAccount = requireNonNull(accounts.get(treasuryId));
        final var random = new SplittableRandom(1_234_567L);
        long balancesTotal = 0L;
        for (long i = FIRST_SYSTEM_FILE_ENTITY; i < FIRST_POST_SYSTEM_FILE_ENTITY; i++) {
            final var id = idFactory.apply(i);
            final long balanceHere = (i >= FIRST_BALANCE_TO_SWEEP) ? random.nextLong(1, ONE_HBAR) : 0L;
            final var legacyAccount = treasuryAccount
                    .copyBuilder()
                    .accountId(id)
                    .tinybarBalance(balanceHere)
                    .build();
            accounts.put(id, legacyAccount);
            balancesTotal += balanceHere;
        }
        accounts.put(
                treasuryId,
                treasuryAccount
                        .copyBuilder()
                        .tinybarBalance(treasuryAccount.tinybarBalance() - balancesTotal)
                        .build());
        tokenStates.commit();

        final var entityStates = (MapWritableStates) fakeState.getWritableStates(EntityIdService.NAME);
        final var countsSingleton = entityStates.<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID);
        final var oldCounts = requireNonNull(countsSingleton.get());
        final var newCounts = oldCounts
                .copyBuilder()
                .numAccounts(oldCounts.numAccounts() + (FIRST_POST_SYSTEM_FILE_ENTITY - FIRST_SYSTEM_FILE_ENTITY))
                .build();
        countsSingleton.put(newCounts);
        entityStates.commit();
    }

    private LongFunction<AccountID> getIdFactoryFor(@NonNull final AccountID sampleId) {
        return num -> sampleId.copyBuilder().accountNum(num).build();
    }

    private boolean isSystemFile(final long entityNum) {
        return FIRST_SYSTEM_FILE_ENTITY <= entityNum && entityNum < FIRST_POST_SYSTEM_FILE_ENTITY;
    }
}
