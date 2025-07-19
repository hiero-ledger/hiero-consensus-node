// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.file.batch;

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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfig;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of FileAppendSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
public class AtomicFileCreateSuite {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    final Stream<DynamicTest> exchangeRateControlAccountIsntCharged() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, 1_000_000_000_000L)),
                balanceSnapshot("pre", EXCHANGE_RATE_CONTROL),
                getFileContents(EXCHANGE_RATES).saveTo("exchangeRates.bin"),
                atomicBatch(fileUpdate(EXCHANGE_RATES)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .path(Path.of("./", "exchangeRates.bin").toString())
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(EXCHANGE_RATE_CONTROL).hasTinyBars(changeFromSnapshot("pre", 0)));
    }

    @HapiTest
    final Stream<DynamicTest> createFailsWithExcessiveLifetime() {
        return hapiTest(cryptoCreate(BATCH_OPERATOR), doWithStartupConfig("entities.maxLifetime", value -> atomicBatch(
                        fileCreate("test")
                                .lifetime(Long.parseLong(value) + 12_345L)
                                .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> createFailsWithMissingSigs() {
        KeyShape shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        SigControl validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
        SigControl invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(fileCreate("test")
                                .waclShape(shape)
                                .sigControl(forKey("test", invalidSig))
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(fileCreate("test")
                                .waclShape(shape)
                                .sigControl(forKey("test", validSig))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
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

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(fileCreate("test")
                                .waclShape(shape)
                                .sigControl(forKey("test", validSig))
                                .withTxnTransform(AtomicFileCreateSuite::replaceTxnNodeAccount)
                                .hasPrecheckFrom(INVALID_NODE_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> precheckRejectsBadEffectiveAutoRenewPeriod() {
        var now = Instant.now();
        System.out.println(now.getEpochSecond());

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(fileCreate("notHere")
                                .lifetime(-60L)
                                .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> targetsAppear() {
        var lifetime = 100_000L;
        var requestedExpiry = Instant.now().getEpochSecond() + lifetime;
        var contents = "SOMETHING".getBytes();
        var newWacl = listOf(SIMPLE, listOf(3), threshOf(1, 3));
        var newWaclSigs = newWacl.signedWith(sigs(ON, sigs(ON, ON, ON), sigs(OFF, OFF, ON)));

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                UtilVerbs.newKeyNamed("newWacl").shape(newWacl),
                atomicBatch(fileCreate("file")
                                .via("createTxn")
                                .contents(contents)
                                .key("newWacl")
                                .expiry(requestedExpiry)
                                .signedBy(GENESIS, "newWacl")
                                .sigControl(ControlForKey.forKey("newWacl", newWaclSigs))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                QueryVerbs.getFileInfo("file")
                        .hasDeleted(false)
                        .hasWacl("newWacl")
                        .hasExpiryPassing(expiry -> expiry == requestedExpiry),
                QueryVerbs.getFileContents("file").hasByteStringContents(ignore -> ByteString.copyFrom(contents)));
    }
}
