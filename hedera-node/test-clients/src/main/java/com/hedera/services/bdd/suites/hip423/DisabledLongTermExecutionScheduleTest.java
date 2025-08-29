// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip423;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.ED_25519_KEY;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GOSSIP_ENDPOINTS_IPS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.SERVICES_ENDPOINTS_IPS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULED_TRANSACTION_NOT_IN_WHITELIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_EXPIRY_NOT_CONFIGURABLE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@HapiTestLifecycle
public class DisabledLongTermExecutionScheduleTest {

    private static final String RECEIVER = "receiver";
    private static final String SENDER = "sender";
    private static final String SENDER_TXN = "senderTxn";
    private static final String CREATE_TXN = "createTxn";
    private static final String PAYER = "payer";
    private static final String BASIC_XFER = "basicXfer";
    private static final String THREE_SIG_XFER = "threeSigXfer";
    private static final String SCHEDULING_LONG_TERM_ENABLED = "scheduling.longTermEnabled";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // override and preserve old values
        lifecycle.overrideInClass(Map.of(
                "scheduling.longTermEnabled",
                "false",
                "scheduling.whitelist",
                "CryptoTransfer,ConsensusSubmitMessage,TokenBurn,TokenMint,CryptoApproveAllowance"));
    }

    @HapiTest
    @Order(1)
    public Stream<DynamicTest> waitForExpiryIgnoredWhenLongTermDisabled() {

        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HBAR),
                cryptoCreate(SENDER).balance(1L).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                THREE_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(SENDER, RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                scheduleSign(THREE_SIG_XFER).alsoSigningWith(PAYER),
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                getScheduleInfo(THREE_SIG_XFER)
                        .hasScheduleId(THREE_SIG_XFER)
                        .hasWaitForExpiry(false)
                        .isExecuted());
    }

    @HapiTest
    @Order(2)
    public Stream<DynamicTest> expiryIgnoredWhenLongTermDisabled() {
        return hapiTest(
                cryptoCreate(SENDER).balance(ONE_HBAR).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                THREE_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .designatingPayer(SENDER),
                scheduleSign(THREE_SIG_XFER).alsoSigningWith(SENDER, RECEIVER),
                getScheduleInfo(THREE_SIG_XFER)
                        .hasScheduleId(THREE_SIG_XFER)
                        .isExecuted()
                        .isNotDeleted(),
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }

    @HapiTest
    @Order(3)
    public Stream<DynamicTest> waitForExpiryIgnoredWhenLongTermDisabledThenEnabled() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HBAR),
                cryptoCreate(SENDER).balance(1L).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                THREE_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(SENDER, RECEIVER),
                getAccountBalance(RECEIVER).hasTinyBars(0L),
                overriding(SCHEDULING_LONG_TERM_ENABLED, "true"),
                scheduleSign(THREE_SIG_XFER).alsoSigningWith(PAYER),
                cryptoCreate("triggerTxn"),
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                getScheduleInfo(THREE_SIG_XFER)
                        .hasScheduleId(THREE_SIG_XFER)
                        .hasWaitForExpiry(false)
                        .isExecuted(),
                overriding(SCHEDULING_LONG_TERM_ENABLED, "false"));
    }

    @HapiTest
    @Order(4)
    public Stream<DynamicTest> expiryIgnoredWhenLongTermDisabledThenEnabled() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HBAR),
                cryptoCreate(SENDER).balance(1L).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(0L).receiverSigRequired(true),
                scheduleCreate(
                                THREE_SIG_XFER,
                                cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYER)
                        .via(CREATE_TXN),
                getScheduleInfo(THREE_SIG_XFER)
                        .hasScheduleId(THREE_SIG_XFER)
                        .hasWaitForExpiry(false)
                        .hasRelativeExpiry(CREATE_TXN, TimeUnit.MINUTES.toSeconds(30))
                        .isNotExecuted()
                        .isNotDeleted(),
                scheduleSign(THREE_SIG_XFER)
                        .alsoSigningWith(PAYER, SENDER, RECEIVER)
                        .payingWith(PAYER),
                getScheduleInfo(THREE_SIG_XFER)
                        .hasScheduleId(THREE_SIG_XFER)
                        .hasWaitForExpiry(false)
                        .hasRelativeExpiry(CREATE_TXN, TimeUnit.MINUTES.toSeconds(30))
                        .isExecuted()
                        .isNotDeleted(),
                getAccountBalance(RECEIVER).hasTinyBars(1L));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> scheduledTestGetsDeletedIfNotExecuted() {
        return hapiTest(
                overriding("scheduling.longTermEnabled", "false"),
                cryptoCreate(PAYER),
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .payingWith(PAYER)
                        .designatingPayer(PAYER)
                        .via(CREATE_TXN),
                getScheduleInfo(BASIC_XFER),
                // Wait for the schedule to expire
                sleepFor(TimeUnit.MINUTES.toMillis(31)),
                cryptoCreate("foo").via("triggerCleanUpTxn"),
                getScheduleInfo(BASIC_XFER).hasCostAnswerPrecheck(INVALID_SCHEDULE_ID));
    }

    @HapiTest
    public Stream<DynamicTest> scheduleWithExpirationTimeAndLongTermSchedulesDisabled() {
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .expiringAt(10)
                        .hasKnownStatus(SCHEDULE_EXPIRY_NOT_CONFIGURABLE));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> scheduleNodeCreateNotSupportedWhenNotInWhitelist() {
        return hapiTest(
                scheduleCreate("schedule", nodeCreate("test")).hasKnownStatus(SCHEDULED_TRANSACTION_NOT_IN_WHITELIST));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleNodeUpdateNotSupportedWhenNotInWhitelist() throws Exception {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                nodeCreate("test")
                        .description("hello")
                        .gossipCaCertificate(
                                generateX509Certificates(2).getFirst().getEncoded())
                        .grpcCertificateHash("hash".getBytes())
                        .accountNum(100)
                        .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                        .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                        .adminKey(ED_25519_KEY),
                scheduleCreate("schedule", nodeUpdate("test").description("hello2"))
                        .hasKnownStatus(SCHEDULED_TRANSACTION_NOT_IN_WHITELIST));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleNodeDeleteNotSupportedWhenNotInWhitelist() throws Exception {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                nodeCreate("test")
                        .description("hello")
                        .gossipCaCertificate(
                                generateX509Certificates(2).getFirst().getEncoded())
                        .grpcCertificateHash("hash".getBytes())
                        .accountNum(100)
                        .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                        .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                        .adminKey(ED_25519_KEY),
                scheduleCreate("payerOnly", nodeDelete("test")).hasKnownStatus(SCHEDULED_TRANSACTION_NOT_IN_WHITELIST));
    }
}
