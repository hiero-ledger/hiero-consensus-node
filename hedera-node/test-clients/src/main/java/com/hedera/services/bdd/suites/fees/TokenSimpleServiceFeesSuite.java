// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.fees.SimpleFeesSuite.ucents_to_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@Tag(MATS)
@HapiTestLifecycle
public class TokenSimpleServiceFeesSuite {
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nonFungibleToken";
    private static final String METADATA_KEY = "metadata-key";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";

    @FunctionalInterface
    public interface OpsProvider {
        List<SpecOperation> provide();
    }

    private Stream<DynamicTest> compare(OpsProvider provider) {
        List<SpecOperation> opsList = new ArrayList<>();
        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        opsList.add(withOpContext((spec, op) -> {
            System.out.println("old fees");
        }));
        opsList.addAll(provider.provide());
        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        opsList.add(withOpContext((spec, op) -> {
            System.out.println("new fees");
        }));
        opsList.addAll(provider.provide());
        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare create fungible token")
    final Stream<DynamicTest> compareCreateFungibleToken() {
        return compare(() -> Arrays.asList(
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .blankMemo()
                        .payingWith(PAYER)
                        .fee(ONE_BILLION_HBARS)
                        .treasury(ADMIN)
                        .tokenType(FUNGIBLE_COMMON)
                        .autoRenewAccount(ADMIN)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .logged()
                        .hasKnownStatus(SUCCESS)
                        .via("create-token-txn"),
                // actual for current fees is ~900_000 even though the spec says 1.0
                validateChargedUsdWithin("create-token-txn", ucents_to_USD(100_000), 15)));
    }

    @HapiTest
    @DisplayName("compare create non-fungible token")
    final Stream<DynamicTest> compareCreateNonFungibleToken() {
        return compare(() -> Arrays.asList(
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
                        .via("create-token-txn"),
                validateChargedUsdWithin("create-token-txn", ucents_to_USD(100000), 10)));
    }

    @HapiTest
    @DisplayName("compare mint common token")
    final Stream<DynamicTest> compareMintCommonToken() {
        return compare(() -> Arrays.asList(
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
                validateChargedUsd("fungible-mint-txn", ucents_to_USD(100))));
    }

    @HapiTest
    @DisplayName("compare mint multiple common tokens")
    final Stream<DynamicTest> compareMintMultipleCommonToken() {
        return compare(() -> Arrays.asList(
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
                validateChargedUsdWithin("fungible-mint-txn", ucents_to_USD(100), 15)));
    }

    @HapiTest
    @DisplayName("compare mint a unique token")
    final Stream<DynamicTest> compareMintUniqueToken() {
        return compare(() -> Arrays.asList(
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
                validateChargedUsdWithin("non-fungible-mint-txn", ucents_to_USD(2000), 15)));
    }

    @HapiTest
    @DisplayName("compare burn a common token")
    final Stream<DynamicTest> compareBurnToken() {
        return compare(() -> Arrays.asList(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .payingWith(PAYER)
                        .supplyKey(SUPPLY_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS),
                mintToken(FUNGIBLE_TOKEN, 10)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS),
                burnToken(FUNGIBLE_TOKEN, 10)
                        .payingWith(PAYER)
                        .hasKnownStatus(SUCCESS)
                        .via("burn-token-txn"),
                validateChargedUsd("burn-token-txn", 0.001, 1)));
    }

    @HapiTest
    @DisplayName("compare delete a common token")
    final Stream<DynamicTest> compareDeleteToken() {
        return compare(() -> Arrays.asList(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .payingWith(PAYER)
                        .adminKey(ADMIN)
                        .supplyKey(SUPPLY_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS),
                mintToken(FUNGIBLE_TOKEN, 10)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS),
                tokenDelete(FUNGIBLE_TOKEN)
                        .purging()
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("delete-token-txn"),
                validateChargedUsd("delete-token-txn", 0.0162, 1)));
    }
}
