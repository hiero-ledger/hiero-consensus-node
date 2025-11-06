package com.hedera.services.bdd.suites.fees;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenSimpleServiceFeesSuite {
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nonFungibleToken";
    private static final String METADATA_KEY = "metadata-key";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";

    static double ucents_to_USD(double amount) {
        return amount / 100_000.0;
    }

    @HapiTest
    @DisplayName("create a fungible token")
    final Stream<DynamicTest> createFungibleToken() {
        return hapiTest(
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .blankMemo()
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .treasury(ADMIN)
                        .tokenType(FUNGIBLE_COMMON)
                        .autoRenewAccount(ADMIN)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .logged()
                        .hasKnownStatus(SUCCESS)
                        .via("create-token-txn")
                ,
                validateChargedUsd("create-token-txn", ucents_to_USD(100000))
        );
    }

    @HapiTest
    @DisplayName("create a non-fungible token")
    final Stream<DynamicTest> createNonFungibleToken() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS),
                tokenCreate("uniqueNoFees")
                        .blankMemo()
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .treasury(ADMIN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .autoRenewAccount(ADMIN)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .logged()
                        .hasKnownStatus(SUCCESS)
                        .via("create-token-txn")
                ,
                validateChargedUsd("create-token-txn", ucents_to_USD(100000))
        );
    }

    @HapiTest
    @DisplayName("mint a common token")
    final Stream<DynamicTest> mintCommonToken() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .payingWith(PAYER)
                        .supplyKey(SUPPLY_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("create-token-txn"),
                mintToken(FUNGIBLE_TOKEN, 1)
                        .payingWith(PAYER)
                        .signedBy(SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("fungible-mint-txn"),
                validateChargedUsd("fungible-mint-txn", 0.001)
        );
    }

    @HapiTest
    @DisplayName("mint multiple common tokens")
    final Stream<DynamicTest> mintMultipleCommonToken() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .payingWith(PAYER)
                        .supplyKey(SUPPLY_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("create-token-txn"),
                mintToken(FUNGIBLE_TOKEN, 10)
                        .payingWith(PAYER)
                        .signedBy(SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("fungible-mint-txn"),
                validateChargedUsd("fungible-mint-txn", 0.001)
        );
    }

    @HapiTest
    @DisplayName("mint a unique token")
    final Stream<DynamicTest> mintUniqueToken() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                tokenCreate(NFT_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .payingWith(PAYER)
                        .supplyKey(SUPPLY_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("create-token-txn"),
                mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("Bart Simpson")))
                        .payingWith(PAYER)
                        .signedBy(SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("non-fungible-mint-txn"),
                validateChargedUsd("non-fungible-mint-txn", 0.001)
        );
    }

}
