// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.junit.restart.RestartType.UPGRADE_BOUNDARY;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.restart.RestartHapiTest;
import com.hedera.services.bdd.junit.restart.SavedStateSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.SplittableRandom;
import java.util.function.LongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * A test that "picks up" after a simulated restart with legacy system accounts in state, and verifies that they
 * are cleaned up as expected by dispatching synthetic {@link HederaFunctionality#CRYPTO_DELETE} txs.
 */
@Order(10)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class LegacySystemAccountCleanupTest implements SavedStateSpec {
    private static final long FIRST_SYSTEM_FILE_ENTITY = 100L;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;

    @RestartHapiTest(restartType = UPGRADE_BOUNDARY, savedStateSpec = LegacySystemAccountCleanupTest.class)
    final Stream<DynamicTest> legacyAccountsCleanedUpPostUpgrade() {
        return hapiTest(
                // Send a burst of rounds through
                blockingOrder(IntStream.range(0, 10)
                        .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)))
                        .toArray(SpecOperation[]::new)),
                withOpContext((spec, opLog) -> {
                    final var accounts = spec.embeddedAccountsOrThrow();
                    for (long i = FIRST_SYSTEM_FILE_ENTITY; i < FIRST_POST_SYSTEM_FILE_ENTITY; i++) {
                        final var id = spec.accountIdFactory().apply(i);
                        final var account = accounts.get(toPbj(id));
                        assertNull(account, "Account #" + i + " should not exist after upgrade");
                    }
                    final var map = (MapWritableKVState<AccountID, Account>) accounts;
                    final var totalHbarBalance = map.getBackingStore().values().stream()
                            .mapToLong(Account::tinybarBalance)
                            .sum();
                    assertEquals(50 * ONE_BILLION_HBARS, totalHbarBalance, "Wrong HBAR balance after upgrade");
                }));
    }

    @Override
    public void accept(@NonNull final FakeState fakeState) {
        final var tokenStates = (MapWritableStates) fakeState.getWritableStates(TokenService.NAME);
        final var accounts = (MapWritableKVState<AccountID, Account>) tokenStates.<AccountID, Account>get(ACCOUNTS_KEY);
        final var sampleId = accounts.getBackingStore().keySet().iterator().next();
        final var idFactory = getIdFactoryFor(sampleId);
        final var treasuryId = idFactory.apply(2L);
        final var treasuryAccount = requireNonNull(accounts.get(treasuryId));
        final var random = new SplittableRandom(1_234_567L);
        long balancesTotal = 0L;
        for (long i = FIRST_SYSTEM_FILE_ENTITY; i < FIRST_POST_SYSTEM_FILE_ENTITY; i++) {
            final var id = idFactory.apply(i);
            final long balanceHere = random.nextLong(ONE_HBAR);
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
    }

    private LongFunction<AccountID> getIdFactoryFor(@NonNull final AccountID sampleId) {
        return num -> sampleId.copyBuilder().accountNum(num).build();
    }
}
