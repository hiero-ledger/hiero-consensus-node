// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file;

import static com.hedera.services.bdd.spec.HapiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withTargetLedgerId;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_DETAILS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.NodeAddressBook;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Transaction;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class FileCreateSuite {
    @HapiTest
    final Stream<DynamicTest> exchangeRateControlAccountIsntCharged() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, 1_000_000_000_000L)),
                balanceSnapshot("pre", EXCHANGE_RATE_CONTROL),
                getFileContents(EXCHANGE_RATES).saveTo("exchangeRates.bin"),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .path(Path.of("./", "exchangeRates.bin").toString()),
                getAccountBalance(EXCHANGE_RATE_CONTROL).hasTinyBars(changeFromSnapshot("pre", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> createFailsWithExcessiveLifetime() {
        return hapiTest(doWithStartupConfig("entities.maxLifetime", value -> fileCreate("test")
                .lifetime(Long.parseLong(value) + 12_345L)
                .hasPrecheck(AUTORENEW_DURATION_NOT_IN_RANGE)));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(submitModified(
                withSuccessivelyVariedBodyIds(), () -> fileCreate("file").contents("ABC")));
    }

    @HapiTest
    final Stream<DynamicTest> createWithMemoWorks() {
        String memo = "Really quite something!";

        return hapiTest(
                fileCreate("ntb").entityMemo(ZERO_BYTE_MEMO).hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                fileCreate("memorable").entityMemo(memo),
                withTargetLedgerId(ledgerId ->
                        getFileInfo("memorable").hasEncodedLedgerId(ledgerId).hasMemo(memo)));
    }

    @HapiTest
    final Stream<DynamicTest> createFailsWithMissingSigs() {
        KeyShape shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        SigControl validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
        SigControl invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return hapiTest(
                fileCreate("test")
                        .waclShape(shape)
                        .sigControl(forKey("test", invalidSig))
                        .hasKnownStatus(INVALID_SIGNATURE),
                fileCreate("test").waclShape(shape).sigControl(forKey("test", validSig)));
    }

    private static Transaction replaceTxnNodeAccount(Transaction txn) {
        AccountID badNodeAccount = AccountID.newBuilder()
                .setAccountNum(2000)
                .setRealmNum(0)
                .setShardNum(0)
                .build();
        return TxnUtils.replaceTxnNodeAccount(txn, badNodeAccount);
    }

    @HapiTest
    final Stream<DynamicTest> createFailsWithPayerAccountNotFound() {
        KeyShape shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        SigControl validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return hapiTest(fileCreate("test")
                .withProtoStructure(HapiSpecSetup.TxnProtoStructure.OLD)
                .waclShape(shape)
                .sigControl(forKey("test", validSig))
                .withTxnTransform(FileCreateSuite::replaceTxnNodeAccount)
                .hasPrecheckFrom(INVALID_NODE_ACCOUNT));
    }

    @HapiTest
    final Stream<DynamicTest> precheckRejectsBadEffectiveAutoRenewPeriod() {
        var now = Instant.now();
        System.out.println(now.getEpochSecond());

        return hapiTest(
                fileCreate("notHere").lifetime(-60L).hasPrecheck(ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE));
    }

    @HapiTest
    final Stream<DynamicTest> targetsAppear() {
        var lifetime = 100_000L;
        var requestedExpiry = Instant.now().getEpochSecond() + lifetime;
        var contents = "SOMETHING".getBytes();
        var newWacl = listOf(SIMPLE, listOf(3), threshOf(1, 3));
        var newWaclSigs = newWacl.signedWith(sigs(ON, sigs(ON, ON, ON), sigs(OFF, OFF, ON)));

        return hapiTest(
                UtilVerbs.newKeyNamed("newWacl").shape(newWacl),
                fileCreate("file")
                        .via("createTxn")
                        .contents(contents)
                        .key("newWacl")
                        .expiry(requestedExpiry)
                        .signedBy(GENESIS, "newWacl")
                        .sigControl(ControlForKey.forKey("newWacl", newWaclSigs)),
                QueryVerbs.getFileInfo("file")
                        .hasDeleted(false)
                        .hasWacl("newWacl")
                        .hasExpiryPassing(expiry -> expiry == requestedExpiry),
                QueryVerbs.getFileContents("file").hasByteStringContents(ignore -> ByteString.copyFrom(contents)));
    }

    @HapiTest
    final Stream<DynamicTest> getsExpectedRejections() {
        return hapiTest(
                fileCreate("tbd"),
                fileDelete("tbd"),
                getFileInfo("1.2.3").nodePayment(1_234L).hasAnswerOnlyPrecheck(INVALID_FILE_ID),
                getFileContents("1.2.3").nodePayment(1_234L).hasAnswerOnlyPrecheck(INVALID_FILE_ID),
                getFileContents("tbd")
                        .nodePayment(1_234L)
                        .hasAnswerOnlyPrecheck(OK)
                        .logged(),
                getFileInfo("tbd")
                        .nodePayment(1_234L)
                        .hasAnswerOnlyPrecheck(OK)
                        .hasDeleted(true)
                        .logged());
    }

    /**
     * Fetches the system files from a running network and validates they can be
     * parsed as the expected protobuf messages.
     */
    @HapiTest
    final Stream<DynamicTest> fetchFiles() {
        return customHapiSpec("FetchFiles")
                .withProperties(Map.of(
                        "fees.useFixedOffer", "true",
                        "fees.fixedOffer", "100000000"))
                .given()
                .when()
                .then(
                        getFileContents(NODE_DETAILS).andValidate(unchecked(NodeAddressBook::parseFrom)::apply),
                        getFileContents(ADDRESS_BOOK).andValidate(unchecked(NodeAddressBook::parseFrom)::apply),
                        getFileContents(EXCHANGE_RATES).andValidate(unchecked(ExchangeRateSet::parseFrom)::apply),
                        getFileContents(APP_PROPERTIES)
                                .andValidate(unchecked(ServicesConfigurationList::parseFrom)::apply),
                        getFileContents(API_PERMISSIONS)
                                .andValidate(unchecked(ServicesConfigurationList::parseFrom)::apply),
                        getFileContents(FEE_SCHEDULE)
                                .fee(300_000L)
                                .nodePayment(40L)
                                .andValidate(unchecked(CurrentAndNextFeeSchedule::parseFrom)::apply));
    }

    @FunctionalInterface
    public interface CheckedParser {
        Object parseFrom(byte[] bytes) throws Exception;
    }

    static Function<byte[], String> unchecked(CheckedParser parser) {
        return bytes -> {
            try {
                return parser.parseFrom(bytes).toString();
            } catch (Exception e) {
                e.printStackTrace();
                return "<N/A> due to " + e.getMessage() + "!";
            }
        };
    }
}
