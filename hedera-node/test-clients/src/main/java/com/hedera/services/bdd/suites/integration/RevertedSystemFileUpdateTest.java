// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.ScheduleID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a system-file update executed by a child dispatch whose parent later rolls back does not change the
 * in-memory facility the file backs. A contract authorizes a scheduled exchange-rate update through the schedule
 * service, which executes the update as a child of the contract call, and then reverts. The active exchange rate must
 * still match the committed file.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RevertedSystemFileUpdateTest {
    @Contract(contract = "RevertingScheduleAuthorizer", creationGas = 1_000_000)
    static SpecContract AUTHORIZER;

    private static final String AUTHORIZER_KEY = "revertingAuthorizerKey";
    private static final String RATE_UPDATE = "rateUpdate";

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> exchangeRateUpdateUnderRevertedContractCallLeavesActiveRateUnchanged() {
        final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();
        final AtomicReference<byte[]> fileBefore = new AtomicReference<>();
        final AtomicReference<byte[]> fileAfter = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateBefore = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateAfter = new AtomicReference<>();
        return hapiTest(
                AUTHORIZER.getInfo(),
                newKeyNamed(AUTHORIZER_KEY).shape(KeyShape.CONTRACT.signedWith(AUTHORIZER.name())),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // Only the contract can now complete a schedule paid by the exchange-rate control account
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(AUTHORIZER_KEY).payingWith(GENESIS),
                scheduleCreate(RATE_UPDATE, fileUpdate(EXCHANGE_RATES).contents(spec -> spec.ratesProvider()
                                .rateSetWith(10, 121)
                                .toByteString()))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS)
                        .exposingCreatedIdTo(scheduleId::set),
                getScheduleInfo(RATE_UPDATE).isNotExecuted(),
                getFileContents(EXCHANGE_RATES).consumedBy(fileBefore::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("before"),
                getTxnRecord("before")
                        .exposingTo(r ->
                                rateBefore.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                // The contract authorizes the schedule, which executes the update, and then reverts
                sourcingContextual(spec -> AUTHORIZER
                        .call(
                                "authorizeScheduleAndRevert",
                                mirrorAddrWith(spec, scheduleId.get().getScheduleNum()))
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getScheduleInfo(RATE_UPDATE).isNotExecuted(),
                getFileContents(EXCHANGE_RATES).consumedBy(fileAfter::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("after"),
                getTxnRecord("after")
                        .exposingTo(r ->
                                rateAfter.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                withOpContext((spec, log) -> {
                    assertArrayEquals(fileBefore.get(), fileAfter.get(), "file 0.0.112 unchanged");
                    assertEquals(rateBefore.get(), rateAfter.get(), "active rate must still match committed 0.0.112");
                }));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> committedScheduledExchangeRateUpdateTakesEffect() {
        final AtomicReference<ExchangeRate> rateAfter = new AtomicReference<>();
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // Signed with the exchange-rate control key, so the schedule executes as a child of its creation
                scheduleCreate("committedRateUpdate", fileUpdate(EXCHANGE_RATES).contents(spec -> spec.ratesProvider()
                                .rateSetWith(10, 121)
                                .toByteString()))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS)
                        .alsoSigningWith(EXCHANGE_RATE_CONTROL),
                getScheduleInfo("committedRateUpdate").isExecuted(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("after"),
                getTxnRecord("after")
                        .exposingTo(r ->
                                rateAfter.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                withOpContext((spec, log) -> {
                    assertEquals(10, rateAfter.get().getHbarEquiv(), "committed rate must be active");
                    assertEquals(121, rateAfter.get().getCentEquiv(), "committed rate must be active");
                }));
    }
}
