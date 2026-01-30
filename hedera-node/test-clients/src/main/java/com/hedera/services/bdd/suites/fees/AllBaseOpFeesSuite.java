// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
@Tag(MATS)
public class AllBaseOpFeesSuite {
    private static final String PAYER = "payer";
    private static final double ALLOWED_DIFFERENCE_PERCENTAGE = 0.01;

    private static final String SUPPLY_KEY = "supplyKey";

    private static final String CIVILIAN_ACCT = "civilian";

    private static final String UNIQUE_TOKEN = "nftType";

    private static final double EXPECTED_NFT_MINT_PRICE_USD = 0.02;

    @HapiTest
    final Stream<DynamicTest> NftMintsScaleLinearlyBasedOnNumberOfSignatures() {
        final var numOfSigs = 10;
        final var expectedFee = EXPECTED_NFT_MINT_PRICE_USD + ((numOfSigs - 1) * SIGNATURE_FEE_AFTER_MULTIPLIER);
        final var standard100ByteMetadata = ByteString.copyFromUtf8(
                "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");

        return defaultHapiSpec("NftMintsScaleLinearlyBasedOnNumberOfSignatures")
                .given(
                        newKeyNamed(SUPPLY_KEY).shape(listOf(numOfSigs)),
                        cryptoCreate(CIVILIAN_ACCT).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        tokenCreate(UNIQUE_TOKEN)
                                .initialSupply(0L)
                                .expiry(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS)
                                .supplyKey(SUPPLY_KEY)
                                .tokenType(NON_FUNGIBLE_UNIQUE))
                .when(mintToken(UNIQUE_TOKEN, List.of(standard100ByteMetadata))
                        .payingWith(CIVILIAN_ACCT)
                        .signedBy(SUPPLY_KEY, SUPPLY_KEY, SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .via("moreSigsTxn"))
                .then(validateChargedUsdWithin("moreSigsTxn", expectedFee, ALLOWED_DIFFERENCE_PERCENTAGE));
    }

    @HapiTest
    final Stream<DynamicTest> payerRecordCreationSanityChecks() {
        return defaultHapiSpec("PayerRecordCreationSanityChecks")
                .given(cryptoCreate(PAYER))
                .when(
                        createTopic("ofGeneralInterest").payingWith(PAYER),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1_000L)).payingWith(PAYER),
                        submitMessageTo("ofGeneralInterest").message("I say!").payingWith(PAYER))
                .then(assertionsHold((spec, opLog) -> {
                    final var payerId = spec.registry().getAccountID(PAYER);
                    final var subOp = getAccountRecords(PAYER).logged();
                    allRunFor(spec, subOp);
                    final var records = subOp.getResponse().getCryptoGetAccountRecords().getRecordsList().stream()
                            .filter(TxnUtils::isNotEndOfStakingPeriodRecord)
                            .toList();
                    assertEquals(3, records.size());
                    for (var record : records) {
                        assertEquals(record.getTransactionFee(), -netChangeIn(record, payerId));
                    }
                }));
    }

    private long netChangeIn(TransactionRecord record, AccountID id) {
        return record.getTransferList().getAccountAmountsList().stream()
                .filter(aa -> id.equals(aa.getAccountID()))
                .mapToLong(AccountAmount::getAmount)
                .sum();
    }
}
