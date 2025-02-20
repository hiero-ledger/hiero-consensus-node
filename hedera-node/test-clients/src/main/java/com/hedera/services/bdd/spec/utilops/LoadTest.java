// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runLoadTest;
import static com.hedera.services.bdd.suites.perf.PerfTestLoadSettings.DEFAULT_INITIAL_BALANCE;
import static com.hedera.services.bdd.suites.perf.PerfTestLoadSettings.DEFAULT_MEMO_LENGTH;
import static java.util.concurrent.TimeUnit.MINUTES;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

public class LoadTest extends HapiSuite {
    private static final Logger log = LogManager.getLogger(LoadTest.class);

    public static OptionalDouble targetTPS = OptionalDouble.empty();
    public static OptionalInt testDurationMinutes = OptionalInt.empty();
    public static OptionalInt threadNumber = OptionalInt.empty();
    public static OptionalInt hcsSubmitMessage = OptionalInt.empty();
    /** initial balance of account used as sender for performance test transactions */
    public static OptionalLong initialBalance = OptionalLong.of(DEFAULT_INITIAL_BALANCE);

    public static OptionalInt memoLength = OptionalInt.of(DEFAULT_MEMO_LENGTH);

    public static int parseArgs(String... args) {
        int usedArgs = 0;
        if (args.length > 0) {
            targetTPS = OptionalDouble.of(Double.parseDouble(args[0]));
            log.info("Set targetTPS as {}", targetTPS.getAsDouble());
            usedArgs++;
        }

        if (args.length > 1) {
            testDurationMinutes = OptionalInt.of(Integer.parseInt(args[1]));
            log.info("Set testDurationMinutes as {}", testDurationMinutes.getAsInt());
            usedArgs++;
        }

        if (args.length > 2) {
            threadNumber = OptionalInt.of(Integer.parseInt(args[2]));
            log.info("Set threadNumber as {}", threadNumber.getAsInt());
            usedArgs++;
        }

        if (args.length > 3) {
            initialBalance = OptionalLong.of(Long.parseLong(args[3]));
            log.info("Set initialBalance as {}", initialBalance.getAsLong());
            usedArgs++;
        }

        if (args.length > 4) {
            hcsSubmitMessage = OptionalInt.of(Integer.parseInt(args[4]));
            log.info("Set hcsSubmitMessageSize as {}", hcsSubmitMessage.getAsInt());
            usedArgs++;
        }

        if (args.length > 5) {
            memoLength = OptionalInt.of(Integer.parseInt(args[5]));
            log.info("Set Memo Length as {}", memoLength.getAsInt());
            usedArgs++;
        }

        return usedArgs;
    }

    public static double getTargetTPS() {
        return targetTPS.getAsDouble();
    }

    public static int getTestDurationMinutes() {
        return testDurationMinutes.getAsInt();
    }

    public static RunLoadTest defaultLoadTest(Supplier<HapiSpecOperation[]> opSource, PerfTestLoadSettings settings) {
        return runLoadTest(opSource)
                .tps(targetTPS.isPresent() ? LoadTest::getTargetTPS : settings::getTps)
                .setNumberOfThreads(threadNumber.isPresent() ? threadNumber::getAsInt : settings::getThreads)
                .lasting(
                        (testDurationMinutes.isPresent() ? LoadTest::getTestDurationMinutes : settings::getMins),
                        () -> MINUTES);
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return null;
    }
}
