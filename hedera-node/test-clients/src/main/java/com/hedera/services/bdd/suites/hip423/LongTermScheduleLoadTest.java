// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip423;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asEvmAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.annotations.FungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(LONG_RUNNING)
@HapiTestLifecycle
public class LongTermScheduleLoadTest {
    private static final String TOPIC = "loadTopic";
    private static final long TEST_DURATION = 5L;
    private static final int SUBMIT_MESSAGE_TPS = 9;
    private static final int SCHEDULE_CREATE_TPS = 1;
    private static final long MAX_SCHEDULE_EXPIRY_SECS = 5L;

    @Account(tinybarBalance = 5 * ONE_MILLION_HBARS)
    static SpecAccount PAYER;

    @Account(tinybarBalance = ONE_HUNDRED_HBARS)
    static SpecAccount RECEIVER;

    @Contract(contract = "HtsTransferFrom", creationGas = 2_000_000L)
    static SpecContract HTS_TRANSFER_FROM;

    @FungibleToken(initialSupply = 1_000_000, decimals = 1)
    static SpecFungibleToken APPLES;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                PAYER.getInfo(),
                APPLES.getInfo(),
                RECEIVER.associateTokens(APPLES),
                HTS_TRANSFER_FROM.getInfo(),
                cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, APPLES.treasury().name(), 10 * ONE_HUNDRED_HBARS)),
                cryptoApproveAllowance()
                        .payingWith(APPLES.treasury().name())
                        .addTokenAllowance(
                                APPLES.treasury().name(), APPLES.name(), HTS_TRANSFER_FROM.name(), 1_000_000L)
                        .fee(ONE_HBAR));
    }

    @HapiTest
    final Stream<DynamicTest> submitsMessagesAndCreatesLongTermSchedulesInParallel() {
        return hapiTest(
                createTopic(TOPIC),
                inParallel(
                        runWithProvider(submitMessageLoad())
                                .lasting(TEST_DURATION, TimeUnit.MINUTES)
                                .maxOpsPerSec(SUBMIT_MESSAGE_TPS)
                                .loggingOff(),
                        runWithProvider(longTermScheduleCreateLoad())
                                .lasting(TEST_DURATION, TimeUnit.MINUTES)
                                .maxOpsPerSec(SCHEDULE_CREATE_TPS)
                                .loggingOff()));
    }

    private Function<HapiSpec, OpProvider> submitMessageLoad() {
        return ignore -> {
            final var seqNo = new AtomicInteger(0);
            return () -> {
                final var message = "direct-submit-" + seqNo.getAndIncrement();
                return Optional.of(submitMessageTo(TOPIC)
                        .payingWith(PAYER.name())
                        .message(message)
                        .deferStatusResolution()
                        .hasPrecheckFrom(OK, BUSY)
                        .noLogging());
            };
        };
    }

    private Function<HapiSpec, OpProvider> longTermScheduleCreateLoad() {
        return spec -> {
            final var seqNo = new AtomicInteger(0);
            return () -> {
                final int n = seqNo.incrementAndGet();
                final var scheduleName = "loadSchedule-" + n;
                // HTS_TRANSFER_FROM.call("htsTransferFrom", APPLES, APPLES.treasury(), RECEIVER, BigInteger.ONE)
                final var network = spec.targetNetworkOrThrow();
                final var apples = asHeadlongAddress(asEvmAddress(
                        APPLES.modelOrThrow(network).tokenIdOrThrow().tokenNum()));
                final var applesTreasury = asHeadlongAddress(asEvmAddress(APPLES.treasury()
                        .modelOrThrow(network)
                        .accountIdOrThrow()
                        .accountNumOrThrow()));
                final var receiver = asHeadlongAddress(asEvmAddress(
                        RECEIVER.modelOrThrow(network).accountIdOrThrow().accountNumOrThrow()));
                return Optional.of(scheduleCreate(
                                scheduleName,
                                contractCall(
                                                HTS_TRANSFER_FROM.name(),
                                                "htsTransferFrom",
                                                apples,
                                                applesTreasury,
                                                receiver,
                                                BigInteger.ONE)
                                        .payingWith(PAYER.name())
                                        .memo(scheduleName)
                                        .gas(1_000_000))
                        .payingWith(PAYER.name())
                        .waitForExpiry(true)
                        .expiringIn(MAX_SCHEDULE_EXPIRY_SECS)
                        .rememberingNothing()
                        .deferStatusResolution()
                        .hasPrecheckFrom(OK, BUSY)
                        .noLogging());
            };
        };
    }
}
