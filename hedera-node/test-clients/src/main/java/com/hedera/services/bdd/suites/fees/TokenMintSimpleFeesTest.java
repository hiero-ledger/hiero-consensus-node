// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_FT_BASE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_MINT_NFT_FEE_USD;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class TokenMintSimpleFeesTest {

    @HapiTest
    final Stream<DynamicTest> mintFungibleFee() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("payer"),
                tokenCreate("FT").tokenType(TokenType.FUNGIBLE_COMMON).supplyKey("supplyKey"),
                mintToken("FT", 500)
                        .fee(ONE_HBAR)
                        .payingWith("payer")
                        .signedBy("supplyKey", "payer")
                        .via("mintTxn"),
                validateChargedUsd("mintTxn", TOKEN_MINT_FT_BASE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER, 0.1));
    }

    @HapiTest
    final Stream<DynamicTest> mintNftBaseFee() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("payer"),
                tokenCreate("NFT")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey("supplyKey"),
                mintToken("NFT", List.of(copyFromUtf8("t1")))
                        .fee(ONE_HBAR)
                        .payingWith("payer")
                        .signedBy("supplyKey", "payer")
                        .via("mintTxn"),
                validateChargedUsd("mintTxn", TOKEN_MINT_NFT_FEE_USD + SIGNATURE_FEE_AFTER_MULTIPLIER, 0.1));
    }

    @HapiTest
    final Stream<DynamicTest> mintNft10Tokens() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("payer"),
                tokenCreate("NFT")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey("supplyKey"),
                mintToken(
                                "NFT",
                                List.of(
                                        copyFromUtf8("t1"),
                                        copyFromUtf8("t2"),
                                        copyFromUtf8("t3"),
                                        copyFromUtf8("t4"),
                                        copyFromUtf8("t5"),
                                        copyFromUtf8("t6"),
                                        copyFromUtf8("t7"),
                                        copyFromUtf8("t8"),
                                        copyFromUtf8("t9"),
                                        copyFromUtf8("t10")))
                        .fee(10 * ONE_HBAR)
                        .payingWith("payer")
                        .signedBy("supplyKey", "payer")
                        .via("mintTxn"),
                validateChargedUsd("mintTxn", 10 * TOKEN_MINT_NFT_FEE_USD + SIGNATURE_FEE_AFTER_MULTIPLIER, 0.1));
    }
}
