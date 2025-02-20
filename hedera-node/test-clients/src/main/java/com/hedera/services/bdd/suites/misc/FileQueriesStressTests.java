// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
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
public class FileQueriesStressTests {
    private final AtomicLong duration = new AtomicLong(10);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(10);

    @HapiTest
    final Stream<DynamicTest> getFileContentsStress() {
        return hapiTest(
                withOpContext((spec, opLog) -> configureFromCi(spec)),
                runWithProvider(getFileContentsFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get));
    }

    @HapiTest
    final Stream<DynamicTest> getFileInfoStress() {
        return hapiTest(
                withOpContext((spec, opLog) -> configureFromCi(spec)),
                runWithProvider(getFileInfoFactory())
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get));
    }

    private Function<HapiSpec, OpProvider> getFileContentsFactory() {
        byte[] contents = "You won't believe this!".getBytes();

        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(fileCreate("something").contents(contents));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                return Optional.of(getFileContents("something")
                        .hasContents(ignore -> contents)
                        .noLogging());
            }
        };
    }

    private Function<HapiSpec, OpProvider> getFileInfoFactory() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(fileCreate("something").contents("You won't believe this!"));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                return Optional.of(getFileInfo("something").noLogging());
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
