// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip423;

import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.PropertySource.asAccount;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithInvalidAmounts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.uploadScheduledContractPrices;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.ED_25519_KEY;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.GOSSIP_ENDPOINTS_IPS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.SERVICES_ENDPOINTS_IPS;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULED_TRANSACTION_NOT_IN_WHITELIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class ScheduleLongTermExecutionTest {
    private static final String PAYING_ACCOUNT = "payingAccount";
    private static final String RECEIVER = "receiver";
    private static final String SENDER = "sender";
    private static final String SENDER_TXN = "senderTxn";
    private static final String FAILED_XFER = "failedXfer";
    private static final long ONE_MINUTE = 60;
    private static final long ONE_MONTH = 2678400;
    private static final long TWO_MONTHS = 5356800;

    @HapiTest
    public Stream<DynamicTest> executionWithInvalidAccountAmountsFails() {
        long transferAmount = 100;
        long senderBalance = 1000L;
        long payingAccountBalance = 1_000_000L;
        long noBalance = 0L;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(payingAccountBalance),
                cryptoCreate(SENDER).balance(senderBalance).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(
                                FAILED_XFER,
                                cryptoTransfer(tinyBarsFromToWithInvalidAmounts(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .designatingPayer(PAYING_ACCOUNT)
                        .recordingScheduledTxn()
                        .hasKnownStatus(INVALID_ACCOUNT_AMOUNTS));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleCreateWithExpiringInMoreThenTwoMonths() {
        return hapiTest(
                cryptoCreate("luckyYou").balance(0L),
                scheduleCreate("payerOnly", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "luckyYou", 1L)))
                        .expiringIn(TWO_MONTHS + 10)
                        .hasKnownStatus(SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleCreateWithNonWhiteListedTransaction() {
        return hapiTest(
                cryptoCreate("luckyYou").balance(0L),
                tokenCreate("testToken"),
                scheduleCreate("payerOnly", tokenAssociate("luckyYou", "testToken"))
                        .expiringIn(ONE_MINUTE)
                        .hasKnownStatus(SCHEDULED_TRANSACTION_NOT_IN_WHITELIST));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleCreateWithNonExistingPayer() {
        return hapiTest(
                cryptoCreate("luckyYou").balance(0L),
                scheduleCreate("payerOnly", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "luckyYou", 1L)))
                        .expiringIn(ONE_MINUTE)
                        .withNonExistingDesignatingPayer()
                        .hasKnownStatus(ACCOUNT_ID_DOES_NOT_EXIST));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleCreateIdenticalTransactions() {
        return hapiTest(
                cryptoCreate("luckyYou").balance(0L).via("cryptoCreate"),
                // Expiring the schedules relative to the cryptoCreate so the expiry time will be exactly the same
                scheduleCreate("payerOnly", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "luckyYou", 1L)))
                        .withRelativeExpiry("cryptoCreate", ONE_MINUTE),
                scheduleCreate("payerOnly", cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, "luckyYou", 1L)))
                        .withRelativeExpiry("cryptoCreate", ONE_MINUTE)
                        .hasKnownStatus(IDENTICAL_SCHEDULE_ALREADY_CREATED));
    }

    @LeakyHapiTest(requirement = FEE_SCHEDULE_OVERRIDES)
    final Stream<DynamicTest> scheduleCreateIdenticalContractCall() {
        final var contract = "CallOperationsChecker";
        return hapiTest(
                // upload fees for SCHEDULE_CREATE_CONTRACT_CALL
                uploadScheduledContractPrices(GENESIS),
                cryptoCreate("luckyYou").balance(0L).via("cryptoCreate"),
                uploadInitCode(contract),
                contractCreate(contract),

                // Expiring the schedules relative to the cryptoCreate so the expiry time will be exactly the same
                scheduleCreate("payerOnly", contractCall(contract)).withRelativeExpiry("cryptoCreate", ONE_MINUTE),
                scheduleCreate("payerOnly", contractCall(contract))
                        .withRelativeExpiry("cryptoCreate", ONE_MINUTE)
                        .hasKnownStatus(IDENTICAL_SCHEDULE_ALREADY_CREATED));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleNodeCreateWorks() throws Exception {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                scheduleCreate(
                                "schedule",
                                nodeCreate("test")
                                        .description("hello")
                                        .gossipCaCertificate(generateX509Certificates(2)
                                                .getFirst()
                                                .getEncoded())
                                        .grpcCertificateHash("hash".getBytes())
                                        .accountId(asAccount(asEntityString(100)))
                                        .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                                        .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                                        .adminKey(ED_25519_KEY))
                        .waitForExpiry()
                        .expiringIn(ONE_MONTH));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleNodeUpdateWorks() throws Exception {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                nodeCreate("test")
                        .description("hello")
                        .gossipCaCertificate(
                                generateX509Certificates(2).getFirst().getEncoded())
                        .grpcCertificateHash("hash".getBytes())
                        .accountId(asAccount(asEntityString(100)))
                        .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                        .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                        .adminKey(ED_25519_KEY),
                scheduleCreate("schedule", nodeUpdate("test").description("hello2"))
                        .waitForExpiry()
                        .expiringIn(ONE_MONTH));
    }

    @HapiTest
    final Stream<DynamicTest> scheduleDeleteUpdateWorks() throws Exception {
        return hapiTest(
                newKeyNamed(ED_25519_KEY).shape(KeyShape.ED25519),
                nodeCreate("test")
                        .description("hello")
                        .gossipCaCertificate(
                                generateX509Certificates(2).getFirst().getEncoded())
                        .grpcCertificateHash("hash".getBytes())
                        .accountId(asAccount(asEntityString(100)))
                        .gossipEndpoint(GOSSIP_ENDPOINTS_IPS)
                        .serviceEndpoint(SERVICES_ENDPOINTS_IPS)
                        .adminKey(ED_25519_KEY),
                scheduleCreate("payerOnly", nodeDelete("test")).expiringIn(ONE_MONTH));
    }
}
