// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites;

import static com.hedera.services.bdd.spec.HapiSpecSetup.NodeSelection.FIXED;
import static com.hedera.services.bdd.spec.HapiSpecSetup.TlsConfig.OFF;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isIdLiteral;
import static com.hedera.services.bdd.suites.HapiSuite.FinalOutcome;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.HapiClients;
import com.hedera.services.bdd.suites.crypto.CryptoCreateForSuiteRunner;
import com.hedera.services.bdd.suites.freeze.FreezeAbort;
import com.hedera.services.bdd.suites.freeze.FreezeUpgrade;
import com.hedera.services.bdd.suites.freeze.PrepareUpgrade;
import com.hedera.services.bdd.suites.freeze.SimpleFreezeOnly;
import com.hedera.services.bdd.suites.freeze.UpdateFileForUpgrade;
import com.hedera.services.bdd.suites.jrs.NodeOpsForUpgrade;
import com.hedera.services.bdd.suites.meta.VersionInfoSpec;
import com.hedera.services.bdd.suites.perf.AdjustFeeScheduleSuite;
import com.hedera.services.bdd.suites.perf.crypto.*;
import com.hedera.services.bdd.suites.perf.topic.SubmitMessageLoadTest;
import com.hedera.services.bdd.suites.reconnect.CreateAccountsBeforeReconnect;
import com.hedera.services.bdd.suites.reconnect.CreateFilesBeforeReconnect;
import com.hedera.services.bdd.suites.reconnect.CreateTopicsBeforeReconnect;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SuiteRunner {
    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private static final Random RANDOM = new Random(544470L);

    private static final Logger log = LogManager.getLogger(SuiteRunner.class);
    public static final int SUITE_NAME_WIDTH = 32;

    private static final HapiSpecSetup.TlsConfig DEFAULT_TLS_CONFIG = OFF;
    private static final HapiSpecSetup.TxnProtoStructure DEFAULT_TXN_CONFIG = HapiSpecSetup.TxnProtoStructure.ALTERNATE;
    private static final HapiSpecSetup.NodeSelection DEFAULT_NODE_SELECTOR = FIXED;
    private static final int EXPECTED_CI_NETWORK_SIZE = 4;
    private static final String DEFAULT_PAYER_ID = "2";

    private static final List<HapiSuite> SUITES_TO_DETAIL = new ArrayList<>();

    @SuppressWarnings({"java:S1171", "java:S3599", "java:S125"})
    private static final Map<String, Supplier<HapiSuite[]>> CATEGORY_MAP = new HashMap<>() {
        {
            put("CryptoTransferLoadTestWithStakedAccounts", aof(CryptoTransferLoadTestWithStakedAccounts::new));
            put("VersionInfoSpec", aof(VersionInfoSpec::new));
            put("UpdateFileForUpgrade", aof(UpdateFileForUpgrade::new));
            put("PrepareUpgrade", aof(PrepareUpgrade::new));
            put("FreezeUpgrade", aof(FreezeUpgrade::new));
            put("SimpleFreezeOnly", aof(SimpleFreezeOnly::new));
            put("FreezeAbort", aof(FreezeAbort::new));
            put("CreateAccountsBeforeReconnect", aof(CreateAccountsBeforeReconnect::new));
            put("CreateTopicsBeforeReconnect", aof(CreateTopicsBeforeReconnect::new));
            put("CreateFilesBeforeReconnect", aof(CreateFilesBeforeReconnect::new));
            put("SubmitMessageLoadTest", aof(SubmitMessageLoadTest::new));
            put("AdjustFeeSchedule", aof(AdjustFeeScheduleSuite::new));
            put("NodeOpsForUpgrade", aof(NodeOpsForUpgrade::new));
        }
    };

    static boolean runAsync;
    static List<CategorySuites> targetCategories;
    static boolean globalPassFlag = true;

    private static final String TLS_ARG = "-TLS";
    private static final String TXN_ARG = "-TXN";
    private static final String NODE_SELECTOR_ARG = "-NODE";
    /* Specify the network size so that we can read the appropriate throttle settings for that network. */
    private static final String NETWORK_SIZE_ARG = "-NETWORKSIZE";
    /* Specify the network to run legacy SC tests instead of using suiterunner */
    private static String payerId = DEFAULT_PAYER_ID;

    @SuppressWarnings("java:S2440")
    public static void main(String... args) throws Exception {
        String[] effArgs = trueArgs(args);
        log.info("Effective args :: {}", List.of(effArgs));
        if (Arrays.asList(effArgs).contains("-CI")) {
            var tlsOverride = overrideOrDefault(effArgs, TLS_ARG, DEFAULT_TLS_CONFIG.toString());
            var txnOverride = overrideOrDefault(effArgs, TXN_ARG, DEFAULT_TXN_CONFIG.toString());
            var nodeSelectorOverride = overrideOrDefault(effArgs, NODE_SELECTOR_ARG, DEFAULT_NODE_SELECTOR.toString());
            int expectedNetworkSize =
                    Integer.parseInt(overrideOrDefault(effArgs, NETWORK_SIZE_ARG, "" + EXPECTED_CI_NETWORK_SIZE)
                            .split("=")[1]);
            var otherOverrides = arbitraryOverrides(effArgs);
            // For HTS perf regression test, we need to know the number of clients to distribute
            // the creation of the test tokens and token associations to each client.
            // For current perf test setup, this number will be the size of test network.
            if (!otherOverrides.containsKey("totalClients")) {
                otherOverrides.put("totalClients", "" + expectedNetworkSize);
            }

            createPayerAccount(System.getenv("NODES"), args[1]);

            HapiSpec.runInCiMode(
                    System.getenv("NODES"),
                    payerId,
                    args[1],
                    tlsOverride.substring(TLS_ARG.length() + 1),
                    txnOverride.substring(TXN_ARG.length() + 1),
                    nodeSelectorOverride.substring(NODE_SELECTOR_ARG.length() + 1),
                    otherOverrides);
        }
        Map<Boolean, List<String>> statefulCategories = Stream.of(effArgs)
                .filter(CATEGORY_MAP::containsKey)
                .collect(groupingBy(cat ->
                        SuiteRunner.categoryLeaksState(CATEGORY_MAP.get(cat).get())));

        Map<String, List<CategoryResult>> byRunType = new HashMap<>();
        if (statefulCategories.get(Boolean.TRUE) != null) {
            runAsync = false;
            byRunType.put("sync", runCategories(statefulCategories.get(Boolean.TRUE)));
        }
        if (statefulCategories.get(Boolean.FALSE) != null) {
            runAsync = true;
            byRunType.put("async", runCategories(statefulCategories.get(Boolean.FALSE)));
        }
        summarizeResults(byRunType);
        HapiClients.tearDown();

        System.exit(globalPassFlag ? 0 : 1);
    }

    /**
     * Create a default payer account for each test client while running JRS regression tests
     *
     * @param nodes
     * @param defaultNode
     */
    private static void createPayerAccount(String nodes, String defaultNode) {
        try {
            Thread.sleep(RANDOM.nextInt(5000));
            new CryptoCreateForSuiteRunner(nodes, defaultNode).runSuiteAsync();
            Thread.sleep(2000);
            if (!isIdLiteral(payerId)) {
                payerId = DEFAULT_PAYER_ID;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String overrideOrDefault(String[] effArgs, String argPrefix, String defaultValue) {
        return Stream.of(effArgs)
                .filter(arg -> arg.startsWith(argPrefix))
                .findAny()
                .orElse(String.format("%s=%s", argPrefix, defaultValue));
    }

    private static Map<String, String> arbitraryOverrides(String[] effArgs) {
        var miscOverridePattern = Pattern.compile("([^-].*?)=(.*)");
        return Stream.of(effArgs)
                .map(miscOverridePattern::matcher)
                .filter(Matcher::matches)
                .collect(toMap(m -> m.group(1), m -> m.group(2)));
    }

    private static String[] trueArgs(String[] args) {
        String ciArgs =
                Optional.ofNullable(System.getenv("DSL_SUITE_RUNNER_ARGS")).orElse("");
        log.info("Args from CircleCI environment: |{}|", ciArgs);

        return StringUtils.isNotEmpty(ciArgs)
                ? Stream.of(args, new String[] {"-CI"}, getEffectiveDSLSuiteRunnerArgs(ciArgs))
                        .flatMap(Stream::of)
                        .toArray(String[]::new)
                : args;
    }

    /**
     * Check if the DSL_SUITE_RUNNER_ARGS contain ALL_SUITES. If so, add all test suites from
     * CATEGORY_MAP to args that should be run.
     *
     * @param realArgs DSL_SUITE_RUNNER_ARGS provided
     * @return effective args after examining DSL_SUITE_RUNNER_ARGS
     */
    private static String[] getEffectiveDSLSuiteRunnerArgs(String realArgs) {
        Set<String> effectiveArgs = new HashSet<>();
        String[] ciArgs = realArgs.split("\\s+");

        if (Arrays.asList(ciArgs).contains("ALL_SUITES")) {
            effectiveArgs.addAll(CATEGORY_MAP.keySet());
            effectiveArgs.addAll(
                    Stream.of(ciArgs).filter(e -> !e.equals("ALL_SUITES")).toList());
            log.info("Effective args when running ALL_SUITES : {}", effectiveArgs);
            return effectiveArgs.toArray(new String[0]);
        }

        return ciArgs;
    }

    private static List<CategoryResult> runCategories(List<String> args) {
        collectTargetCategories(args);
        return runTargetCategories();
    }

    private static void summarizeResults(Map<String, List<CategoryResult>> byRunType) {
        byRunType.forEach((key, results) -> {
            log.info("============== {} run results ==============", key);
            for (CategoryResult result : results) {
                log.info(result.summary);
                for (HapiSuite failed : result.failedSuites) {
                    String specList = failed.getFinalSpecs().stream()
                            .filter(HapiSpec::notOk)
                            .map(HapiSpec::toString)
                            .collect(joining(", "));
                    log.info("  --> Problems in suite '{}' :: {}", failed.name(), specList);
                }
                globalPassFlag &= result.failedSuites.isEmpty();
            }
        });
        log.info("============== SuiteRunner finished ==============");

        /* Print detail summaries for analysis by HapiClientValidator */
        SUITES_TO_DETAIL.forEach(HapiSuite::summarizeDeferredResults);
    }

    private static boolean categoryLeaksState(HapiSuite[] suites) {
        return Stream.of(suites).anyMatch(suite -> !suite.canRunConcurrent());
    }

    private static List<CategoryResult> runTargetCategories() {
        if (runAsync) {
            return accumulateAsync(
                    targetCategories.toArray(CategorySuites[]::new), sbc -> runSuitesAsync(sbc.category, sbc.suites));
        } else {
            return targetCategories.stream()
                    .map(sbc -> runSuitesSync(sbc.category, sbc.suites))
                    .toList();
        }
    }

    @SuppressWarnings("java:S3864")
    private static void collectTargetCategories(List<String> args) {
        targetCategories = args.stream()
                .filter(k -> null != CATEGORY_MAP.get(k))
                .map(k -> new CategorySuites(
                        rightPadded(k, SUITE_NAME_WIDTH), CATEGORY_MAP.get(k).get()))
                .peek(cs -> List.of(cs.suites).forEach(suite -> {
                    suite.skipClientTearDown();
                    suite.deferResultsSummary();
                    SUITES_TO_DETAIL.add(suite);
                }))
                .toList();
    }

    private static CategoryResult runSuitesAsync(String category, HapiSuite[] suites) {
        List<FinalOutcome> outcomes = accumulateAsync(suites, HapiSuite::runSuiteAsync);
        List<HapiSuite> failed = IntStream.range(0, suites.length)
                .filter(i -> outcomes.get(i) != FinalOutcome.SUITE_PASSED)
                .mapToObj(i -> suites[i])
                .toList();
        return summaryOf(category, suites, failed);
    }

    private static CategoryResult runSuitesSync(String category, HapiSuite[] suites) {
        List<HapiSuite> failed = Stream.of(suites)
                .filter(suite -> suite.runSuiteSync() != FinalOutcome.SUITE_PASSED)
                .toList();
        return summaryOf(category, suites, failed);
    }

    private static CategoryResult summaryOf(String category, HapiSuite[] suites, List<HapiSuite> failed) {
        int numPassed = suites.length - failed.size();
        String summary = category + " :: " + numPassed + "/" + suites.length + " suites ran ok";
        return new CategoryResult(summary, failed);
    }

    private static <T, R> List<R> accumulateAsync(T[] inputs, Function<T, R> f) {
        final List<R> outputs = new ArrayList<>();
        for (int i = 0; i < inputs.length; i++) {
            outputs.add(null);
        }
        CompletableFuture<Void> future = CompletableFuture.allOf(IntStream.range(0, inputs.length)
                .mapToObj(i -> runAsync(() -> outputs.set(i, f.apply(inputs[i])), HapiSpec.getCommonThreadPool()))
                .toArray(CompletableFuture[]::new));
        future.join();
        return outputs;
    }

    static class CategoryResult {
        final String summary;
        final List<HapiSuite> failedSuites;

        CategoryResult(String summary, List<HapiSuite> failedSuites) {
            this.summary = summary;
            this.failedSuites = failedSuites;
        }
    }

    static class CategorySuites {
        final String category;
        final HapiSuite[] suites;

        CategorySuites(String category, HapiSuite[] suites) {
            this.category = category;
            this.suites = suites;
        }
    }

    public static String rightPadded(String s, int width) {
        if (s.length() == width) {
            return s;
        } else if (s.length() > width) {
            int cutLen = (width - 3) / 2;
            return s.substring(0, cutLen) + "..." + s.substring(s.length() - cutLen);
        } else {
            return s
                    + IntStream.range(0, width - s.length())
                            .mapToObj(ignore -> " ")
                            .collect(joining(""));
        }
    }

    @SafeVarargs
    public static Supplier<HapiSuite[]> aof(Supplier<HapiSuite>... items) {
        return () -> {
            HapiSuite[] suites = new HapiSuite[items.length];
            for (int i = 0; i < items.length; i++) {
                suites[i] = items[i].get();
            }
            return suites;
        };
    }

    public static void setPayerId(String payerId) {
        SuiteRunner.payerId = payerId;
    }
}
