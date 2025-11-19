// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.compareSimpleToOld;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@Tag(MATS)
@HapiTestLifecycle
public class TokenServiceSimpleFeesSuite {
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nonFungibleToken";
    private static final String METADATA_KEY = "metadata-key";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";
    private static final String OTHER = "other";

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare create fungible token")
    final Stream<DynamicTest> compareCreateFungibleToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("create-token-txn")),
                "create-token-txn",
                1.0001000,
                1,
                1,
                1);
    }

//    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
//    @DisplayName("compare create non-fungible token")
//    final Stream<DynamicTest> compareCreateNonFungibleToken() {
//        return compareSimpleToOld(
//                () -> Arrays.asList(
//                        newKeyNamed(SUPPLY_KEY),
//                        cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
//                        cryptoCreate(PAYER).balance(ONE_BILLION_HBARS),
//                        tokenCreate("uniqueNoFees")
//                                .blankMemo()
//                                .payingWith(PAYER)
//                                .fee(ONE_HUNDRED_HBARS)
//                                .treasury(ADMIN)
//                                .tokenType(NON_FUNGIBLE_UNIQUE)
//                                .initialSupply(0L)
//                                .supplyKey(SUPPLY_KEY)
//                                .autoRenewAccount(ADMIN)
//                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
//                                .logged()
//                                .hasKnownStatus(SUCCESS)
//                                .via("create-token-txn")),
//                "create-token-txn",
//                2,
//                1,
//                2,
//                1);
//    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare mint common token")
    final Stream<DynamicTest> compareMintCommonToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungible-mint-txn")),
                "fungible-mint-txn",
                0.0011,
                1,
                0.0011,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare mint multiple common tokens")
    final Stream<DynamicTest> compareMintMultipleCommonToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("fungible-mint-txn")),
                "fungible-mint-txn",
                0.0011,
                1,
                0.0011,
                1);
    }

//    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
//    @DisplayName("compare mint a unique token")
//    final Stream<DynamicTest> compareMintUniqueToken() {
//        return compareSimpleToOld(
//                () -> Arrays.asList(
//                        newKeyNamed(SUPPLY_KEY),
//                        newKeyNamed(METADATA_KEY),
//                        cryptoCreate(ADMIN).balance(ONE_BILLION_HBARS),
//                        cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
//                        tokenCreate(NFT_TOKEN)
//                                .tokenType(NON_FUNGIBLE_UNIQUE)
//                                .initialSupply(0L)
//                                .payingWith(PAYER)
//                                .supplyKey(SUPPLY_KEY)
//                                .fee(ONE_HUNDRED_HBARS)
//                                .hasKnownStatus(SUCCESS)
//                                .via("create-token-txn"),
//                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("Bart Simpson")))
//                                .payingWith(PAYER)
//                                .signedBy(SUPPLY_KEY)
//                                .blankMemo()
//                                .fee(ONE_HUNDRED_HBARS)
//                                .hasKnownStatus(SUCCESS)
//                                .via("non-fungible-mint-txn")),
//                "non-fungible-mint-txn",
//                0.02,
//                1,
//                0.02,
//                1);
//    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare pause a common token")
    final Stream<DynamicTest> comparePauseToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(PAUSE_KEY),
                        cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .pauseKey(PAUSE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenPause(FUNGIBLE_TOKEN)
                                .via("pause-token-txn")),
                "pause-token-txn",
                // TODO: actual result being set to zero for some reason
                0.002,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare unpause a common token")
    final Stream<DynamicTest> compareUnpauseToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .pauseKey(PAUSE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenPause(FUNGIBLE_TOKEN),
                        tokenUnpause(FUNGIBLE_TOKEN)
                                .via("unpause-token-txn")),
                "unpause-token-txn",
                // TODO: actual result being set to zero for some reason
                0.002,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare freeze a common token")
    final Stream<DynamicTest> compareFreezeToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER,FUNGIBLE_TOKEN),
                        mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenFreeze(FUNGIBLE_TOKEN,OTHER)
                                .via("freeze-token-txn")),
                "freeze-token-txn",
                // TODO: actual result being set to zero for some reason
                0.002,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare unfreeze a common token")
    final Stream<DynamicTest> compareUnfreezeToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_BILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER),
                        newKeyNamed(FREEZE_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER,FUNGIBLE_TOKEN),
                        mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenFreeze(FUNGIBLE_TOKEN,OTHER),
                        tokenUnfreeze(FUNGIBLE_TOKEN,OTHER)
                                .via("unfreeze-token-txn")),
                "unfreeze-token-txn",
                // TODO: actual result being set to zero for some reason
                0.002,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare burn a common token")
    final Stream<DynamicTest> compareBurnToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("burn-token-txn")),
                "burn-token-txn",
                0.001,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare delete a common token")
    final Stream<DynamicTest> compareDeleteToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
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
                                .via("delete-token-txn")),
                "delete-token-txn",
                0.002,
                1,
                0.002,
                1);
    }
}
