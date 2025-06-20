// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.reconnect;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.utilops.LoadTest.defaultLoadTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.suites.reconnect.CreateAccountsBeforeReconnect.DEFAULT_MINS_FOR_RECONNECT_TESTS;
import static com.hedera.services.bdd.suites.reconnect.CreateAccountsBeforeReconnect.DEFAULT_THREADS_FOR_RECONNECT_TESTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class CreateTopicsBeforeReconnect extends HapiSuite {
    private static final Logger log = LogManager.getLogger(CreateTopicsBeforeReconnect.class);

    private static final int TOPIC_CREATION_LIMIT = 20_000;
    private static final int TOPIC_CREATION_RECONNECT_TPS = 120;

    public static void main(String... args) {
        new CreateTopicsBeforeReconnect().runSuiteSync();
    }

    private static final AtomicInteger topicNumber = new AtomicInteger(0);

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(runCreateTopics());
    }

    private synchronized HapiSpecOperation generateTopicCreateOperation() {
        final long topic = topicNumber.getAndIncrement();
        if (topic >= TOPIC_CREATION_LIMIT) {
            return noOp();
        }

        return createTopic("topic" + topic)
                .noLogging()
                .hasRetryPrecheckFrom(BUSY, DUPLICATE_TRANSACTION, PLATFORM_TRANSACTION_NOT_CREATED)
                .deferStatusResolution();
    }

    final Stream<DynamicTest> runCreateTopics() {
        PerfTestLoadSettings settings = new PerfTestLoadSettings(
                TOPIC_CREATION_RECONNECT_TPS, DEFAULT_MINS_FOR_RECONNECT_TESTS, DEFAULT_THREADS_FOR_RECONNECT_TESTS);

        Supplier<HapiSpecOperation[]> createBurst = () -> new HapiSpecOperation[] {generateTopicCreateOperation()};

        return defaultHapiSpec("RunCreateTopics")
                .given(logIt(ignore -> settings.toString()))
                .when()
                .then(defaultLoadTest(createBurst, settings));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
