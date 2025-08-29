// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.factoryFrom;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class UmbrellaRedux {
    public static final String DEFAULT_PROPERTIES = "regression-mixed_ops.properties";

    private final AtomicLong duration = new AtomicLong(120);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(100);
    private final AtomicInteger maxPendingOps = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger backoffSleepSecs = new AtomicInteger(1);
    private final AtomicInteger statusTimeoutSecs = new AtomicInteger(5);
    private final AtomicReference<String> props = new AtomicReference<>(DEFAULT_PROPERTIES);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);

    @HapiTest
    @Tag(NOT_REPEATABLE)
    final Stream<DynamicTest> umbrellaRedux() {
        return hapiTest(
                withOpContext((spec, opLog) -> {
                    configureFromCi(spec);
                    // use ci property statusTimeoutSecs to overwrite default value
                    // of status.wait.timeout.ms
                    spec.addOverrideProperties(
                            Map.of("status.wait.timeout.ms", Integer.toString(1_000 * statusTimeoutSecs.get())));
                }),
                sourcing(() -> runWithProvider(factoryFrom(props::get))
                        .lasting(duration::get, unit::get)
                        .maxOpsPerSec(maxOpsPerSec::get)
                        .maxPendingOps(maxPendingOps::get)
                        .backoffSleepSecs(backoffSleepSecs::get)));
    }

    private void configureFromCi(HapiSpec spec) {
        HapiPropertySource ciProps = spec.setup().ciPropertiesMap();
        if (ciProps.has("duration")) {
            duration.set(ciProps.getLong("duration"));
        }
        if (ciProps.has("unit")) {
            unit.set(ciProps.getTimeUnit("unit"));
        }
        if (ciProps.has("maxOpsPerSec")) {
            maxOpsPerSec.set(ciProps.getInteger("maxOpsPerSec"));
        }
        if (ciProps.has("props")) {
            props.set(ciProps.get("props"));
        }
        if (ciProps.has("maxPendingOps")) {
            maxPendingOps.set(ciProps.getInteger("maxPendingOps"));
        }
        if (ciProps.has("backoffSleepSecs")) {
            backoffSleepSecs.set(ciProps.getInteger("backoffSleepSecs"));
        }
        if (ciProps.has("statusTimeoutSecs")) {
            statusTimeoutSecs.set(ciProps.getInteger("statusTimeoutSecs"));
        }
        if (ciProps.has("secondsWaitingServerUp")) {
            statusTimeoutSecs.set(ciProps.getInteger("secondsWaitingServerUp"));
        }
    }
}
