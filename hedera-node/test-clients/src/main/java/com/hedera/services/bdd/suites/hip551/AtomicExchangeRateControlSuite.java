// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.ADHOC;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of ExchangeRateControlSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@OrderedInIsolation
@Tag(ADHOC)
public class AtomicExchangeRateControlSuite {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    final HapiFileUpdate resetRatesOp = fileUpdate(EXCHANGE_RATES)
            .payingWith(EXCHANGE_RATE_CONTROL)
            .fee(ADEQUATE_FUNDS)
            .contents(spec -> spec.ratesProvider().rateSetWith(1, 12).toByteString());

    @HapiTest
    final Stream<DynamicTest> acct57CanMakeSmallChanges() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                resetRatesOp,
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .contents(spec -> {
                                    ByteString newRates = spec.ratesProvider()
                                            .rateSetWith(10, 121)
                                            .toByteString();
                                    spec.registry().saveBytes("newRates", newRates);
                                    return newRates;
                                })
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getFileContents(EXCHANGE_RATES)
                        .hasContents(spec -> spec.registry().getBytes("newRates")),
                resetRatesOp);
    }

    @HapiTest
    final Stream<DynamicTest> midnightRateChangesWhenAcct50UpdatesFile112() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                resetRatesOp,
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .contents(spec -> {
                                    ByteString newRates = spec.ratesProvider()
                                            .rateSetWith(10, 254)
                                            .toByteString();
                                    spec.registry().saveBytes("newRates", newRates);
                                    return newRates;
                                })
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .fee(1_000_000_000)
                                .hasKnownStatus(EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> {
                                            ByteString newRates = spec.ratesProvider()
                                                    .rateSetWith(1, 25)
                                                    .toByteString();
                                            spec.registry().saveBytes("newRates", newRates);
                                            return newRates;
                                        })
                                        .payingWith(SYSTEM_ADMIN)
                                        .fee(1_000_000_000)
                                        .batchKey(BATCH_OPERATOR),
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> {
                                            ByteString newRates = spec.ratesProvider()
                                                    .rateSetWith(10, 254)
                                                    .toByteString();
                                            spec.registry().saveBytes("newRates", newRates);
                                            return newRates;
                                        })
                                        .payingWith(EXCHANGE_RATE_CONTROL)
                                        .fee(1_000_000_000)
                                        .batchKey(BATCH_OPERATOR),
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> {
                                            ByteString newRates = spec.ratesProvider()
                                                    .rateSetWith(1, 13, 1, 15)
                                                    .toByteString();
                                            spec.registry().saveBytes("newRates", newRates);
                                            return newRates;
                                        })
                                        .payingWith(SYSTEM_ADMIN)
                                        .fee(1_000_000_000)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> anonCantUpdateRates() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                resetRatesOp,
                cryptoCreate("randomAccount"),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .contents("Should be impossible!")
                                .payingWith("randomAccount")
                                .hasPrecheck(AUTHORIZATION_FAILED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> acct57CantMakeLargeChanges() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                resetRatesOp,
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .contents(spec ->
                                        spec.ratesProvider().rateSetWith(1, 25).toByteString())
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .hasKnownStatus(EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
