// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that an atomic batch cannot carry a system-file update. Each case submits
 * {@code [system-file update, inner that fails]} and expects the batch to be rejected with
 * {@code BATCH_TRANSACTION_IN_BLACKLIST}; the committed file is unchanged and the facility it backs still behaves
 * exactly as the committed file dictates.
 */
@Tag(ATOMIC_BATCH)
@OrderedInIsolation
class AtomicBatchSystemFileBlacklistTest {

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String BROKE = "broke";
    private static final long FEE_SCALE = 100;

    /**
     * An inner that passes precheck/ingest (paid by the funded batch operator) but deterministically fails at
     * handle: it moves an hbar out of a zero-balance account.
     */
    private static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer failingInner() {
        return cryptoTransfer(tinyBarsFromTo(BROKE, FUNDING, com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR))
                .payingWith(BATCH_OPERATOR)
                .signedBy(BATCH_OPERATOR, BROKE)
                .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                .batchKey(BATCH_OPERATOR);
    }

    @HapiTest
    final Stream<DynamicTest> exchangeRateUpdateIsRejectedInBatch() {
        final AtomicReference<byte[]> fileBefore = new AtomicReference<>();
        final AtomicReference<byte[]> fileAfter = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateBefore = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateAfter = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BROKE).balance(0L),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ADEQUATE_FUNDS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 12).toByteString()),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                getFileContents(EXCHANGE_RATES).consumedBy(fileBefore::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("before"),
                getTxnRecord("before")
                        .exposingTo(r ->
                                rateBefore.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                atomicBatch(
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> spec.ratesProvider()
                                                .rateSetWith(10, 121)
                                                .toByteString())
                                        .payingWith(EXCHANGE_RATE_CONTROL)
                                        .batchKey(BATCH_OPERATOR),
                                failingInner())
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                getFileContents(EXCHANGE_RATES).consumedBy(fileAfter::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("after"),
                getTxnRecord("after")
                        .exposingTo(r ->
                                rateAfter.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                withOpContext((spec, log) -> {
                    assertArrayEquals(fileBefore.get(), fileAfter.get(), "file 0.0.112 unchanged");
                    assertEquals(rateBefore.get(), rateAfter.get(), "active rate must still match committed 0.0.112");
                }));
    }

    /**
     * A system-file update dispatched as a child of a batch inner is rejected too. With {@code ScheduleSign} allowed
     * in batches, an inner {@code ScheduleSign} supplies the last signature a scheduled exchange-rate update needs,
     * and a later inner fails. The scheduled update must not run, so the active rate still matches the committed
     * file.
     */
    @LeakyHapiTest(overrides = {"atomicBatch.blacklist"})
    final Stream<DynamicTest> scheduledExchangeRateUpdateTriggeredInBatchIsRejected() {
        final AtomicReference<byte[]> fileBefore = new AtomicReference<>();
        final AtomicReference<byte[]> fileAfter = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateBefore = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateAfter = new AtomicReference<>();
        return hapiTest(
                overriding("atomicBatch.blacklist", "Freeze,AtomicBatch,ScheduleCreate"),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BROKE).balance(0L),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ADEQUATE_FUNDS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 12).toByteString()),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // Only the batch operator signs, so the schedule waits for the exchange-rate control signature
                scheduleCreate("rateUpdate", fileUpdate(EXCHANGE_RATES).contents(spec -> spec.ratesProvider()
                                .rateSetWith(10, 121)
                                .toByteString()))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(BATCH_OPERATOR),
                getScheduleInfo("rateUpdate").isNotExecuted(),
                getFileContents(EXCHANGE_RATES).consumedBy(fileBefore::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("before"),
                getTxnRecord("before")
                        .exposingTo(r ->
                                rateBefore.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                atomicBatch(
                                scheduleSign("rateUpdate")
                                        .alsoSigningWith(EXCHANGE_RATE_CONTROL)
                                        .payingWith(BATCH_OPERATOR)
                                        .batchKey(BATCH_OPERATOR),
                                failingInner())
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getFileContents(EXCHANGE_RATES).consumedBy(fileAfter::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("after"),
                getTxnRecord("after")
                        .exposingTo(r ->
                                rateAfter.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                withOpContext((spec, log) -> {
                    assertArrayEquals(fileBefore.get(), fileAfter.get(), "file 0.0.112 unchanged");
                    assertEquals(rateBefore.get(), rateAfter.get(), "active rate must still match committed 0.0.112");
                }));
    }

    @HapiTest
    final Stream<DynamicTest> simpleFeeScheduleUpdateIsRejectedInBatch() {
        final AtomicReference<byte[]> fileBefore = new AtomicReference<>();
        final AtomicReference<byte[]> fileAfter = new AtomicReference<>();
        final AtomicLong feeBefore = new AtomicLong();
        final AtomicLong feeAfter = new AtomicLong();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BROKE).balance(0L),
                cryptoCreate("measurer").balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, ONE_MILLION_HBARS))
                        .fee(ONE_HUNDRED_HBARS),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(fileBefore::set),
                cryptoTransfer(tinyBarsFromTo("measurer", FUNDING, 1))
                        .payingWith("measurer")
                        .fee(ONE_HUNDRED_HBARS)
                        .via("before"),
                getTxnRecord("before").exposingTo(r -> feeBefore.set(r.getTransactionFee())),
                atomicBatch(
                                fileUpdate(SIMPLE_FEE_SCHEDULE)
                                        .contents(spec ->
                                                ByteString.copyFrom(scaledSchedule(fileBefore.get(), FEE_SCALE)))
                                        .payingWith(FEE_SCHEDULE_CONTROL)
                                        .fee(ONE_HUNDRED_HBARS)
                                        .batchKey(BATCH_OPERATOR),
                                failingInner())
                        .payingWith(BATCH_OPERATOR)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(fileAfter::set),
                cryptoTransfer(tinyBarsFromTo("measurer", FUNDING, 1))
                        .payingWith("measurer")
                        .fee(ONE_HUNDRED_HBARS)
                        .via("after"),
                getTxnRecord("after").exposingTo(r -> feeAfter.set(r.getTransactionFee())),
                withOpContext((spec, log) -> {
                    assertArrayEquals(fileBefore.get(), fileAfter.get(), "file 0.0.113 unchanged");
                    assertEquals(feeBefore.get(), feeAfter.get(), "fee must still follow committed 0.0.113");
                }));
    }

    @HapiTest
    final Stream<DynamicTest> networkPropertiesUpdateIsRejectedInBatch() {
        // 0.0.121: a rejected lowering of ledger.transfers.maxLen must not take effect
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BROKE).balance(0L),
                cryptoCreate("a"),
                cryptoCreate("b"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, ONE_MILLION_HBARS))
                        .fee(ONE_HUNDRED_HBARS),
                atomicBatch(
                                fileUpdate(APP_PROPERTIES)
                                        .payingWith(ADDRESS_BOOK_CONTROL)
                                        .overridingProps(Map.of("ledger.transfers.maxLen", "2"))
                                        .batchKey(BATCH_OPERATOR),
                                failingInner())
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                // Under committed 0.0.121 the default maxLen (10) applies, so this 4-adjustment transfer succeeds;
                // maxLen=2 would reject it with TRANSFER_LIST_SIZE_LIMIT_EXCEEDED.
                cryptoTransfer(tinyBarsFromTo(GENESIS, "a", 1), tinyBarsFromTo(GENESIS, "b", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> apiPermissionsUpdateIsRejectedInBatch() {
        // 0.0.122: a rejected restriction of tokenCreate must not take effect
        final String civilian = "civilian";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BROKE).balance(0L),
                cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, ONE_MILLION_HBARS))
                        .fee(ONE_HUNDRED_HBARS),
                atomicBatch(
                                fileUpdate(API_PERMISSIONS)
                                        .payingWith(ADDRESS_BOOK_CONTROL)
                                        .overridingProps(Map.of("tokenCreate", "0-1"))
                                        .batchKey(BATCH_OPERATOR),
                                failingInner())
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                // Under committed 0.0.122 tokenCreate is open (0-*), so a civilian succeeds; the restriction
                // (0-1) would reject it with UNAUTHORIZED.
                tokenCreate("poc").payingWith(civilian));
    }

    @HapiTest
    final Stream<DynamicTest> systemFileAppendIsRejectedInBatch() {
        final AtomicReference<byte[]> fileBefore = new AtomicReference<>();
        final AtomicReference<byte[]> fileAfter = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                getFileContents(EXCHANGE_RATES).consumedBy(fileBefore::set),
                atomicBatch(fileAppend(EXCHANGE_RATES)
                                .content("appended")
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                getFileContents(EXCHANGE_RATES).consumedBy(fileAfter::set),
                withOpContext(
                        (spec, log) -> assertArrayEquals(fileBefore.get(), fileAfter.get(), "file 0.0.112 unchanged")));
    }

    @HapiTest
    final Stream<DynamicTest> nonSystemFileUpdateIsAllowedInBatch() {
        final String plainFile = "plainFile";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                fileCreate(plainFile).contents("before"),
                atomicBatch(fileUpdate(plainFile).contents("after").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileContents(plainFile).hasContents(spec -> "after".getBytes()));
    }

    private static byte[] scaledSchedule(final byte[] original, final long scale) {
        try {
            final var s = FeeSchedule.PROTOBUF.parse(Bytes.wrap(original));
            final var raised = s.copyBuilder()
                    .node(s.nodeOrThrow()
                            .copyBuilder()
                            .baseFee(s.nodeOrThrow().baseFee() * scale)
                            .build())
                    .extras(s.extras().stream()
                            .map(e -> e.copyBuilder().fee(e.fee() * scale).build())
                            .toList())
                    .services(s.services().stream()
                            .map(sv -> sv.copyBuilder()
                                    .schedule(sv.schedule().stream()
                                            .map(d -> d.copyBuilder()
                                                    .baseFee(d.baseFee() * scale)
                                                    .build())
                                            .toList())
                                    .build())
                            .toList())
                    .build();
            return FeeSchedule.PROTOBUF.toBytes(raised).toByteArray();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
