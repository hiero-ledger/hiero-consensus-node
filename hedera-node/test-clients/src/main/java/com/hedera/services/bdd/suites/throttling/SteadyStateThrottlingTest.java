// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.resourceAsString;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.withoutAutoRestoring;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.records.ContractRecordsSanityCheckSuite.PAYABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECEIPT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.base.Stopwatch;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountBalance;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(LONG_RUNNING)
@OrderedInIsolation
public class SteadyStateThrottlingTest {
    private static final int REGRESSION_NETWORK_SIZE = 4;

    private static final double THROUGHPUT_LIMITS_XFER_NETWORK_TPS = 100.0;
    private static final double THROUGHPUT_LIMITS_FUNGIBLE_MINT_NETWORK_TPS = 30.0;

    private static final double PRIORITY_RESERVATIONS_CONTRACT_CALL_NETWORK_TPS = 2.0;
    private static final double CREATION_LIMITS_CRYPTO_CREATE_NETWORK_TPS = 1.0;
    private static final double BALANCE_QUERY_LIMITS_QPS = 100.0;

    private static final int NETWORK_SIZE = REGRESSION_NETWORK_SIZE;

    private static final double EXPECTED_XFER_TPS = THROUGHPUT_LIMITS_XFER_NETWORK_TPS / NETWORK_SIZE;
    private static final double EXPECTED_FUNGIBLE_MINT_TPS = THROUGHPUT_LIMITS_FUNGIBLE_MINT_NETWORK_TPS / NETWORK_SIZE;
    private static final double EXPECTED_CONTRACT_CALL_TPS =
            PRIORITY_RESERVATIONS_CONTRACT_CALL_NETWORK_TPS / NETWORK_SIZE;
    private static final double EXPECTED_CRYPTO_CREATE_TPS = CREATION_LIMITS_CRYPTO_CREATE_NETWORK_TPS / NETWORK_SIZE;
    private static final double EXPECTED_GET_BALANCE_QPS = BALANCE_QUERY_LIMITS_QPS / NETWORK_SIZE;
    private static final double TOLERATED_PERCENT_DEVIATION = 7;
    private static final String SUPPLY = "supply";
    private static final String TOKEN = "token";
    private static final String CIVILIAN = "civilian";
    /**
     * In general, only {@code BUSY} and {@code SUCCESS} will be returned by the network ({@code BUSY} if we exhaust
     * all retries for a particular transaction without ever submitting it); however, in CI with fewer CPUs available,
     * occasionally {@code RECEIPT_NOT_FOUND} may also be returned if a client thread is starved.
     */
    private static final ResponseCodeEnum[] PERMITTED_STATUSES =
            new ResponseCodeEnum[] {BUSY, SUCCESS, RECEIPT_NOT_FOUND};

    private final AtomicLong duration = new AtomicLong(180);
    private final AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(500);

    private static final SysFileOverrideOp throttleOverrideOp =
            withoutAutoRestoring(THROTTLES, () -> resourceAsString("testSystemFiles/artificial-limits.json"));

    @HapiTest
    @Order(1)
    final Stream<DynamicTest> setArtificialLimits() {
        return hapiTest(throttleOverrideOp);
    }

    @HapiTest
    @Order(2)
    final Stream<DynamicTest> checkXfersTps() {
        return checkTps("Xfers", EXPECTED_XFER_TPS, xferOps());
    }

    @HapiTest
    @Order(3)
    final Stream<DynamicTest> checkFungibleMintsTps() {
        return checkTps("FungibleMints", EXPECTED_FUNGIBLE_MINT_TPS, fungibleMintOps());
    }

    @HapiTest
    @Order(4)
    final Stream<DynamicTest> checkContractCallsTps() {
        return checkTps("ContractCalls", EXPECTED_CONTRACT_CALL_TPS, scCallOps());
    }

    @HapiTest
    @Order(5)
    final Stream<DynamicTest> checkCryptoCreatesTps() {
        return checkTps("CryptoCreates", EXPECTED_CRYPTO_CREATE_TPS, cryptoCreateOps());
    }

    @HapiTest
    @Order(6)
    final Stream<DynamicTest> checkBalanceQps() {
        return checkBalanceQps(50, EXPECTED_GET_BALANCE_QPS);
    }

    @HapiTest
    @Order(7)
    final Stream<DynamicTest> restoreDevLimits() {
        return hapiTest(withOpContext((spec, opLog) -> throttleOverrideOp.restoreContentsIfNeeded(spec)));
    }

    final Stream<DynamicTest> checkTps(String txn, double expectedTps, Function<HapiSpec, OpProvider> provider) {
        return checkCustomNetworkTps(txn, expectedTps, provider, Collections.emptyMap());
    }

    /**
     * An example of how to use this in a spot check of previewnet NFT mint throttle is,
     *
     * <pre>{@code
     * checkCustomNetworkTps(
     *   "NonFungibleMints",
     *   EXPECTED_PREVIEWNET_NON_FUNGIBLE_MINT_TPS,
     *   nonFungibleMintOps(), Map.of(
     *     "nodes", "35.231.208.148",
     *     "default.payer.pemKeyLoc", "[SUPERUSER_PEM]",
     *     "default.payer.pemKeyPassphrase", "[SUPERUSER_PEM_PASSPHRASE]")));
     * }</pre>
     */
    @SuppressWarnings("java:S5960")
    final Stream<DynamicTest> checkCustomNetworkTps(
            String txn, double expectedTps, Function<HapiSpec, OpProvider> provider, Map<String, String> custom) {
        final var name = "Throttles" + txn + "AsExpected";
        final var baseSpec =
                custom.isEmpty() ? defaultHapiSpec(name) : customHapiSpec(name).withProperties(custom);
        final var competingClient = competingClientFor(txn);
        final AtomicInteger numProviderSetupOps = new AtomicInteger(0);
        final Function<HapiSpec, OpProvider> wrappedProvider = spec -> {
            final var opProvider = provider.apply(spec);
            numProviderSetupOps.set(opProvider.suggestedInitializers().size());
            return opProvider;
        };
        return baseSpec.given()
                .when(inParallel(
                        runWithProvider(wrappedProvider)
                                .lasting(duration::get, unit::get)
                                .maxOpsPerSec(maxOpsPerSec::get),
                        competingClient.op()))
                .then(withOpContext((spec, opLog) -> {
                    final int numRelevantSuccesses =
                            spec.finalAdhoc() - competingClient.numSetupOps() - numProviderSetupOps.get();
                    var actualTps = 1.0 * numRelevantSuccesses / duration.get();
                    var percentDeviation = Math.abs(actualTps / expectedTps - 1.0) * 100.0;
                    opLog.info(
                            "Total ops accepted in {} {} = {} ==> {}tps vs {}tps" + " expected ({}% deviation)",
                            duration.get(),
                            unit.get(),
                            spec.finalAdhoc(),
                            String.format("%.3f", actualTps),
                            String.format("%.3f", expectedTps),
                            String.format("%.3f", percentDeviation));
                    Assertions.assertEquals(0.0, percentDeviation, TOLERATED_PERCENT_DEVIATION);
                }));
    }

    private record CompetingClient(int numSetupOps, SpecOperation op) {}

    private CompetingClient competingClientFor(String txn) {
        return switch (txn) {
            case "ContractCalls" ->
                new CompetingClient(
                        3,
                        blockingOrder(
                                uploadInitCode(PAYABLE_CONTRACT),
                                contractCreate(PAYABLE_CONTRACT),
                                cryptoCreate(CIVILIAN).balance(ONE_MILLION_HBARS),
                                runWithProvider(spec -> () -> Optional.of(
                                                contractCall(PAYABLE_CONTRACT, "deposit", BigInteger.valueOf(1_000L))
                                                        .fee(10 * ONE_HBAR)
                                                        .deferStatusResolution()
                                                        .hasPrecheckFrom(INVALID_SIGNATURE, BUSY, OK)
                                                        .payingWith(CIVILIAN)
                                                        .signedBy(GENESIS)
                                                        .hasKnownStatusFrom(PERMITTED_STATUSES)
                                                        .noLogging()))
                                        .lasting(duration::get, unit::get)
                                        .maxOpsPerSec(maxOpsPerSec::get)));
            default ->
                new CompetingClient(
                        2,
                        blockingOrder(
                                createTopic("ntb"),
                                cryptoCreate(CIVILIAN).balance(ONE_MILLION_HBARS),
                                runWithProvider(spec -> () -> Optional.of(submitMessageTo("ntb")
                                                .omittingTopicId()
                                                .fee(ONE_HBAR)
                                                .deferStatusResolution()
                                                .hasPrecheckFrom(INVALID_TOPIC_ID, BUSY)
                                                .payingWith(CIVILIAN)
                                                .hasKnownStatusFrom(PERMITTED_STATUSES)
                                                .noLogging()))
                                        .lasting(duration::get, unit::get)
                                        .maxOpsPerSec(maxOpsPerSec::get)));
        };
    }

    final Stream<DynamicTest> checkBalanceQps(int burstSize, double expectedQps) {
        return defaultHapiSpec("CheckBalanceQps")
                .given(cryptoCreate(CIVILIAN), cryptoCreate("curious").payingWith(GENESIS), sleepForSeconds(1))
                .when()
                .then(withOpContext((spec, opLog) -> {
                    int numBusy = 0;
                    int askedSoFar = 0;
                    int secsToRun = (int) duration.get();
                    var watch = Stopwatch.createStarted();
                    int logScreen = 0;
                    while (watch.elapsed(SECONDS) < secsToRun) {
                        var subOps = IntStream.range(0, burstSize)
                                .mapToObj(ignore -> getAccountBalance(CIVILIAN)
                                        .noLogging()
                                        .payingWith("curious")
                                        .hasAnswerOnlyPrecheckFrom(BUSY, OK))
                                .toArray(HapiSpecOperation[]::new);
                        var burst = inParallel(subOps);
                        allRunFor(spec, burst);
                        askedSoFar += burstSize;
                        for (int i = 0; i < burstSize; i++) {
                            var op = (HapiGetAccountBalance) subOps[i];
                            if (op.getResponse().getCryptogetAccountBalance().getBalance() == 0) {
                                numBusy++;
                            }
                        }
                        if (logScreen++ % 100 == 0) {
                            opLog.info(
                                    "{}/{} queries BUSY so far in {}ms",
                                    numBusy,
                                    askedSoFar,
                                    watch.elapsed(TimeUnit.MILLISECONDS));
                        }
                    }
                    var elapsedMs = watch.elapsed(TimeUnit.MILLISECONDS);
                    var numAnswered = askedSoFar - numBusy;
                    var actualQps = (1.0 * numAnswered) / elapsedMs * 1000.0;
                    var percentDeviation = Math.abs(actualQps / expectedQps - 1.0) * 100.0;
                    opLog.info(
                            "Total ops accepted in {} {} = {} ==> {}qps vs {}qps" + " expected ({}% deviation)",
                            elapsedMs,
                            "ms",
                            numAnswered,
                            String.format("%.3f", actualQps),
                            String.format("%.3f", expectedQps),
                            String.format("%.3f", percentDeviation));
                    Assertions.assertEquals(0.0, percentDeviation, TOLERATED_PERCENT_DEVIATION);
                }));
    }

    private Function<HapiSpec, OpProvider> xferOps() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        cryptoCreate(CIVILIAN)
                                .payingWith(GENESIS)
                                .balance(ONE_MILLION_HBARS)
                                .withRecharging(),
                        cryptoCreate("nobody").payingWith(GENESIS).balance(0L));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                var op = cryptoTransfer(tinyBarsFromTo(CIVILIAN, "nobody", 1))
                        .noLogging()
                        .deferStatusResolution()
                        .payingWith(CIVILIAN)
                        .hasPrecheckFrom(OK, BUSY)
                        .hasKnownStatusFrom(PERMITTED_STATUSES);
                return Optional.of(op);
            }
        };
    }

    private Function<HapiSpec, OpProvider> cryptoCreateOps() {
        var i = new AtomicInteger(0);

        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(cryptoCreate(CIVILIAN)
                        .payingWith(GENESIS)
                        .balance(ONE_MILLION_HBARS)
                        .withRecharging());
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                var op = cryptoCreate("w/e" + i.getAndIncrement())
                        .noLogging()
                        .deferStatusResolution()
                        .payingWith(CIVILIAN)
                        .hasPrecheckFrom(OK, BUSY);
                return Optional.of(op);
            }
        };
    }

    private Function<HapiSpec, OpProvider> scCallOps() {
        final var contract = "Multipurpose";
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        uploadInitCode(contract),
                        contractCreate(contract).payingWith(GENESIS),
                        cryptoCreate(CIVILIAN).balance(ONE_MILLION_HBARS).payingWith(GENESIS));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                var op = contractCall(contract)
                        .noLogging()
                        .deferStatusResolution()
                        .payingWith(CIVILIAN)
                        .sending(ONE_HBAR)
                        .hasKnownStatusFrom(SUCCESS)
                        .hasPrecheckFrom(OK, BUSY);
                return Optional.of(op);
            }
        };
    }

    private Function<HapiSpec, OpProvider> fungibleMintOps() {
        return spec -> new OpProvider() {
            @Override
            public List<SpecOperation> suggestedInitializers() {
                return List.of(
                        newKeyNamed(SUPPLY),
                        cryptoCreate(TOKEN_TREASURY).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                        tokenCreate(TOKEN).treasury(TOKEN_TREASURY).supplyKey(SUPPLY));
            }

            @Override
            public Optional<HapiSpecOperation> get() {
                var op = mintToken(TOKEN, 1L)
                        .fee(ONE_HBAR)
                        .noLogging()
                        .rememberingNothing()
                        .deferStatusResolution()
                        .signedBy(TOKEN_TREASURY, SUPPLY)
                        .payingWith(TOKEN_TREASURY)
                        // The last "known status" can still be BUSY if we exhaust retries
                        .hasKnownStatusFrom(PERMITTED_STATUSES)
                        .hasPrecheckFrom(OK, BUSY);
                return Optional.of(op);
            }
        };
    }
}
