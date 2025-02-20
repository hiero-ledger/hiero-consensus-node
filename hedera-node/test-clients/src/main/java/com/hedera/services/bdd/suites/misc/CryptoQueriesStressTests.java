// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(NOT_REPEATABLE)
public class CryptoQueriesStressTests {
    private final AtomicLong duration = new AtomicLong(10);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(10);

    @HapiTest
    final Stream<DynamicTest> getAccountBalanceStress() {
        return hapiTest(
                withOpContext((spec, opLog) -> configureFromCi(spec)),
                runWithProvider(getAccountBalanceFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get));
    }

    @HapiTest
    final Stream<DynamicTest> getAccountInfoStress() {
        return hapiTest(
                withOpContext((spec, opLog) -> configureFromCi(spec)),
                runWithProvider(getAccountInfoFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get));
    }

    @HapiTest
    final Stream<DynamicTest> getAccountRecordsStress() {
        return hapiTest(
                withOpContext((spec, opLog) -> configureFromCi(spec)),
                runWithProvider(getAccountRecordsFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get));
    }

    private Function<HapiSpec, OpProvider> getAccountRecordsFactory() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        cryptoCreate("somebody").sendThreshold(1L),
                        cryptoTransfer(tinyBarsFromTo("somebody", FUNDING, 2L)).via("first"),
                        cryptoTransfer(tinyBarsFromTo("somebody", FUNDING, 3L)).via("second"),
                        cryptoTransfer(tinyBarsFromTo("somebody", FUNDING, 4L)).via("third"));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                return Optional.of(getAccountRecords("somebody")
                        .has(inOrder(
                                recordWith().txnId("first"),
                                recordWith().txnId("second"),
                                recordWith().txnId("third")))
                        .noLogging());
            }
        };
    }

    private Function<HapiSpec, OpProvider> getAccountBalanceFactory() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(cryptoCreate("somebody"));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                return Optional.of(getAccountBalance("somebody").noLogging());
            }
        };
    }

    private Function<HapiSpec, OpProvider> getAccountInfoFactory() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(cryptoCreate("somebody"));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                return Optional.of(getAccountInfo("somebody").noLogging());
            }
        };
    }

    private void configureFromCi(HapiSpec spec) {
        HapiPropertySource ciProps = spec.setup().ciPropertiesMap();
        configure("duration", duration::set, ciProps, ciProps::getLong);
        configure("unit", unit::set, ciProps, ciProps::getTimeUnit);
        configure("maxOpsPerSec", maxOpsPerSec::set, ciProps, ciProps::getInteger);
    }

    private <T> void configure(
            String name, Consumer<T> configurer, HapiPropertySource ciProps, Function<String, T> getter) {
        if (ciProps.has(name)) {
            configurer.accept(getter.apply(name));
        }
    }
}
