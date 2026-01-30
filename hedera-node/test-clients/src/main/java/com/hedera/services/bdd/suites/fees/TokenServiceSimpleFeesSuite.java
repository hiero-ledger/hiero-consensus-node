// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdateNfts;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.compareSimpleToOld;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedSimpleFees;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.nodeFeeFromBytesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_DELETE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_FREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_PAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNFREEZE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UNPAUSE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_BASE_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
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
public class TokenServiceSimpleFeesSuite {
    private static final double TOKEN_ASSOCIATE_FEE = 0.05;
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nonFungibleToken";
    private static final String METADATA_KEY = "metadata-key";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String KYC_KEY = "kycKey";
    private static final String WIPE_KEY = "kycKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String PAYER = "payer";
    private static final String ADMIN = "admin";
    private static final String OTHER = "other";
    private static final String HBAR_COLLECTOR = "hbarCollector";
    private static final int NODE_INCLUDED_BYTES = 1024;
    private static final int NETWORK_MULTIPLIER = 9;

    private static double feeWithExtraSignatures(final double serviceBaseUsd, final long extraSignatures) {
        final double nodeFeeUsd = NODE_BASE_FEE_USD + (extraSignatures * SIGNATURE_FEE_USD);
        return serviceBaseUsd + nodeFeeUsd * (NETWORK_MULTIPLIER + 1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare create fungible token")
    final Stream<DynamicTest> compareCreateFungibleToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .blankMemo()
                                .payingWith(PAYER)
                                .fee(ONE_MILLION_HBARS)
                                .treasury(ADMIN)
                                .tokenType(FUNGIBLE_COMMON)
                                .autoRenewAccount(ADMIN)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .logged()
                                .hasKnownStatus(SUCCESS)
                                .via("create-token-txn")),
                "create-token-txn",
                // base = 0,
                // fungible = 9999000000,
                // node+network = 1000000
                // total = 10000000000 = 1.0
                1.0000,
                1,
                0.912085398,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare create non-fungible token")
    final Stream<DynamicTest> compareCreateNonFungibleToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
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
                                .via("create-token-txn")),
                "create-token-txn",
                // base = 0,
                // fungible = 19999000000,
                // node+network = 1000000
                // total = 20000000000 = 2.0
                1,
                1,
                0.9122951064,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare create fungible token with custom fees")
    final Stream<DynamicTest> compareCreateFungibleTokenWithCustomFees() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                        cryptoCreate(HBAR_COLLECTOR).balance(0L),
                        tokenCreate("commonCustomFees")
                                .blankMemo()
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .treasury(ADMIN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .autoRenewAccount(ADMIN)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .withCustom(fixedHbarFee(1L, HBAR_COLLECTOR))
                                .logged()
                                .hasKnownStatus(SUCCESS)
                                .via("create-token-txn")),
                "create-token-txn",
                2,
                1,
                1.8234970992,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare update fungible token")
    final Stream<DynamicTest> compareUpdateFungibleToken() {
        final var supplyKeySimple = SUPPLY_KEY + "_simple";
        final var newSupplyKeySimple = "NEW_SUPPLY_KEY_SIMPLE";
        final var payerSimple = PAYER + "_simple";
        final var adminSimple = ADMIN + "_simple";
        final var tokenSimple = FUNGIBLE_TOKEN + "_simple";
        final var updateSimpleTxn = "update-token-txn-simple";

        final var supplyKeyOld = SUPPLY_KEY + "_old";
        final var newSupplyKeyOld = "NEW_SUPPLY_KEY_OLD";
        final var payerOld = PAYER + "_old";
        final var adminOld = ADMIN + "_old";
        final var tokenOld = FUNGIBLE_TOKEN + "_old";
        final var updateOldTxn = "update-token-txn-old";

        final var baseFee = feeWithExtraSignatures(TOKEN_UPDATE_BASE_FEE_USD, 2);
        final var oldExpectedFee = 0.0022946124;

        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(supplyKeySimple),
                newKeyNamed(newSupplyKeySimple),
                cryptoCreate(adminSimple).balance(ONE_MILLION_HBARS),
                cryptoCreate(payerSimple).balance(ONE_MILLION_HBARS),
                tokenCreate(tokenSimple)
                        .payingWith(payerSimple)
                        .fee(ONE_MILLION_HBARS)
                        .supplyKey(supplyKeySimple)
                        .tokenType(FUNGIBLE_COMMON)
                        .hasKnownStatus(SUCCESS),
                tokenUpdate(tokenSimple)
                        .payingWith(payerSimple)
                        .fee(ONE_MILLION_HBARS)
                        .signedBy(payerSimple, supplyKeySimple, newSupplyKeySimple)
                        .supplyKey(newSupplyKeySimple)
                        .hasKnownStatus(SUCCESS)
                        .via(updateSimpleTxn),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, updateSimpleTxn);
                    final var expectedFee = baseFee + nodeFeeFromBytesUsd(signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", updateSimpleTxn, expectedFee, 1));
                }),
                overriding("fees.simpleFeesEnabled", "false"),
                newKeyNamed(supplyKeyOld),
                newKeyNamed(newSupplyKeyOld),
                cryptoCreate(adminOld).balance(ONE_MILLION_HBARS),
                cryptoCreate(payerOld).balance(ONE_MILLION_HBARS),
                tokenCreate(tokenOld)
                        .payingWith(payerOld)
                        .fee(ONE_MILLION_HBARS)
                        .supplyKey(supplyKeyOld)
                        .tokenType(FUNGIBLE_COMMON)
                        .hasKnownStatus(SUCCESS),
                tokenUpdate(tokenOld)
                        .payingWith(payerOld)
                        .fee(ONE_MILLION_HBARS)
                        .signedBy(payerOld, supplyKeyOld, newSupplyKeyOld)
                        .supplyKey(newSupplyKeyOld)
                        .hasKnownStatus(SUCCESS)
                        .via(updateOldTxn),
                withOpContext((spec, log) -> {
                    allRunFor(spec, validateChargedSimpleFees("Old Fees", updateOldTxn, oldExpectedFee, 1));
                }));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare mint common token")
    final Stream<DynamicTest> compareMintCommonToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                // base = 0,
                // fungible = 9000000,
                // node+network = 1000000
                // total = 10000000 = .0010
                0.001,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare mint multiple common tokens")
    final Stream<DynamicTest> compareMintMultipleCommonToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                // base = 9000000,
                // fungible = 0*10,
                // node+network = 1000000
                // total = 10000000 = .001
                0.001,
                1,
                0.0010,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare mint a unique token")
    final Stream<DynamicTest> compareMintUniqueToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                                .via("non-fungible-mint-txn")),
                "non-fungible-mint-txn",
                // base = 9000000,
                // nft =  1*199000000,
                // node+network = 1000000
                // total = 209000000 = .0209
                0.0209,
                1,
                0.02,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare mint multiple unique tokens")
    final Stream<DynamicTest> compareMintMultipleUniqueToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(METADATA_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)
                                .via("create-token-txn"),
                        mintToken(
                                        NFT_TOKEN,
                                        List.of(
                                                ByteString.copyFromUtf8("Bart Simpson"),
                                                ByteString.copyFromUtf8("Lisa Simpson"),
                                                ByteString.copyFromUtf8("Homer Simpson")))
                                .payingWith(PAYER)
                                .signedBy(SUPPLY_KEY)
                                .blankMemo()
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)
                                .via("non-fungible-multiple-mint-txn")),
                "non-fungible-multiple-mint-txn",
                // TODO: we need a better way to represent the cost of minting NFTs.
                // with this current system the cost of node+network will be double counted
                // base = 9000000,
                // tokens = 199000000*3,
                // node+network = 1000000
                // total = 607000000 = .00607
                0.0607,
                1,
                0.06,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare pause a common token")
    final Stream<DynamicTest> comparePauseToken() {
        final var expectedSimpleFee = feeWithExtraSignatures(TOKEN_PAUSE_BASE_FEE_USD, 1);
        final var expectedOldFee = 0.0016414884;
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(PAUSE_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("pause-token-txn")),
                "pause-token-txn",
                expectedSimpleFee,
                1,
                expectedOldFee,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare unpause a common token")
    final Stream<DynamicTest> compareUnpauseToken() {
        final var expectedSimpleFee = feeWithExtraSignatures(TOKEN_UNPAUSE_BASE_FEE_USD, 1);
        final var expectedOldFee = 0.0016420032;
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unpause-token-txn")),
                "unpause-token-txn",
                expectedSimpleFee,
                1,
                expectedOldFee,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare freeze a common token")
    final Stream<DynamicTest> compareFreezeToken() {
        final var expectedSimpleFee = feeWithExtraSignatures(TOKEN_FREEZE_BASE_FEE_USD, 1);
        final var expectedOldFee = 0.0016378128;
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .freezeKey(FREEZE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenFreeze(FUNGIBLE_TOKEN, OTHER)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("freeze-token-txn")),
                "freeze-token-txn",
                expectedSimpleFee,
                1,
                expectedOldFee,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare unfreeze a common token")
    final Stream<DynamicTest> compareUnfreezeToken() {
        final var expectedSimpleFee = feeWithExtraSignatures(TOKEN_UNFREEZE_BASE_FEE_USD, 1);
        final var expectedOldFee = 0.001638324;
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenFreeze(FUNGIBLE_TOKEN, OTHER),
                        tokenUnfreeze(FUNGIBLE_TOKEN, OTHER)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unfreeze-token-txn")),
                "unfreeze-token-txn",
                expectedSimpleFee,
                1,
                expectedOldFee,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare burn a common token")
    final Stream<DynamicTest> compareBurnToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                                .fee(ONE_HUNDRED_HBARS)
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
        final var expectedSimpleFee = feeWithExtraSignatures(TOKEN_DELETE_BASE_FEE_USD, 1);
        final var expectedOldFee = 0.0016285608;
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
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
                expectedSimpleFee,
                1,
                expectedOldFee,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare associate a token")
    final Stream<DynamicTest> compareAssociateToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .adminKey(ADMIN)
                                .supplyKey(SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN)
                                .payingWith(OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("token-associate-txn")),
                "token-associate-txn",
                0.05,
                1,
                0.05,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare dissociate a token")
    final Stream<DynamicTest> compareDissociateToken() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .adminKey(ADMIN)
                                .supplyKey(SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        tokenDissociate(OTHER, FUNGIBLE_TOKEN).payingWith(OTHER).via("token-dissociate-txn")),
                "token-dissociate-txn",
                0.05,
                1,
                0.0507303192,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare grant kyc")
    final Stream<DynamicTest> compareGrantKyc() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(KYC_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS).key(KYC_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .adminKey(ADMIN)
                                .supplyKey(SUPPLY_KEY)
                                .kycKey(KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        grantTokenKyc(FUNGIBLE_TOKEN, OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .signedBy(OTHER)
                                .payingWith(OTHER)
                                .via("token-grant-kyc-txn")),
                "token-grant-kyc-txn",
                0.001,
                1,
                0.0010128108,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare revoke kyc")
    final Stream<DynamicTest> compareRevokeKyc() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(KYC_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS).key(KYC_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .adminKey(ADMIN)
                                .supplyKey(SUPPLY_KEY)
                                .kycKey(KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        grantTokenKyc(FUNGIBLE_TOKEN, OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(OTHER),
                        revokeTokenKyc(FUNGIBLE_TOKEN, OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .signedBy(OTHER)
                                .payingWith(OTHER)
                                .via("token-revoke-kyc-txn")),
                "token-revoke-kyc-txn",
                0.001,
                1,
                0.0010130676,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare reject")
    final Stream<DynamicTest> compareReject() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(KYC_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS).key(KYC_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(ADMIN)
                                .initialSupply(1000L)
                                .payingWith(PAYER)
                                .adminKey(ADMIN)
                                .supplyKey(SUPPLY_KEY)
                                .kycKey(KYC_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        // TODO: why do we need kyc to do the transfer?
                        grantTokenKyc(FUNGIBLE_TOKEN, OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(OTHER),
                        cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(ADMIN, OTHER))
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(ADMIN),
                        tokenReject(rejectingToken(FUNGIBLE_TOKEN))
                                .fee(ONE_HUNDRED_HBARS)
                                .signedBy(OTHER)
                                .payingWith(OTHER)
                                .via("token-reject-txn")),
                "token-reject-txn",
                0.001,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare token account wipe")
    final Stream<DynamicTest> compareTokenAccountWipe() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS).key(WIPE_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(ADMIN)
                                .initialSupply(100L)
                                .payingWith(PAYER)
                                .adminKey(ADMIN)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenAssociate(OTHER, FUNGIBLE_TOKEN),
                        mintToken(FUNGIBLE_TOKEN, 100)
                                .payingWith(PAYER)
                                .signedBy(SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(ADMIN, OTHER))
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(ADMIN),
                        wipeTokenAccount(FUNGIBLE_TOKEN, OTHER, 80)
                                .payingWith(OTHER)
                                .signedBy(OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("token-wipe-txn")),
                "token-wipe-txn",
                0.001,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare token fee schedule update")
    final Stream<DynamicTest> compareTokenFeeScheduleUpdate() {
        final var htsAmount = 2_345L;
        final var feeDenom = "denom";
        final var htsCollector = "denomFee";
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        cryptoCreate(htsCollector),
                        tokenCreate(feeDenom).treasury(htsCollector),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS).key(FEE_SCHEDULE_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        tokenFeeScheduleUpdate(FUNGIBLE_TOKEN)
                                .payingWith(OTHER)
                                .signedBy(OTHER)
                                .fee(ONE_HUNDRED_HBARS)
                                .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                                .via("token-fee-schedule-update-txn")),
                "token-fee-schedule-update-txn",
                0.001,
                1,
                0.0010141548,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare token update nfts")
    final Stream<DynamicTest> compareTokenUpdateNFTs() {
        final String NFT_TEST_METADATA = " test metadata";
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        cryptoCreate(OTHER).balance(ONE_MILLION_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .payingWith(PAYER)
                                .supplyKey(SUPPLY_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        mintToken(
                                NFT_TOKEN,
                                List.of(
                                        copyFromUtf8("a"),
                                        copyFromUtf8("b"),
                                        copyFromUtf8("c"),
                                        copyFromUtf8("d"),
                                        copyFromUtf8("e"),
                                        copyFromUtf8("f"),
                                        copyFromUtf8("g"))),
                        tokenUpdateNfts(NFT_TOKEN, NFT_TEST_METADATA, List.of(7L))
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("token-update-nfts-txn")),
                "token-update-nfts-txn",
                0.001,
                1,
                0.001,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare TokenGetInfoQuery")
    final Stream<DynamicTest> compareTokenGetInfo() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(FREEZE_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .treasury(PAYER)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .hasKnownStatus(SUCCESS),
                        getTokenInfo(FUNGIBLE_TOKEN)
                                .hasTotalSupply(1000L)
                                .via("get-token-info-query")
                                .payingWith(PAYER)),
                "get-token-info-query",
                0.0001,
                1,
                0.0001011936,
                1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare TokenGetNftInfoQuery")
    final Stream<DynamicTest> compareTokenGetNftInfo() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .supplyKey(SUPPLY_KEY)
                                .hasKnownStatus(SUCCESS),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("Bart Simpson")))
                                .signedBy(SUPPLY_KEY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS),
                        getTokenNftInfo(NFT_TOKEN, 1L)
                                .hasMetadata(ByteString.copyFromUtf8("Bart Simpson"))
                                .hasSerialNum(1L)
                                .hasCostAnswerPrecheck(OK)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("get-token-nft-info-query")),
                "get-token-nft-info-query",
                0.0001,
                1,
                0.0001012704,
                1);
    }

    @HapiTest
    final Stream<DynamicTest> associateOneFtTokenWithoutCustomFees() {
        return associateBulkTokensAndValidateFees(List.of("token1"));
    }

    @HapiTest
    final Stream<DynamicTest> associateBulkFtTokensWithoutCustomFees() {
        return associateBulkTokensAndValidateFees(List.of("token1", "token2", "token3", "token4"));
    }

    private Stream<DynamicTest> associateBulkTokensAndValidateFees(final List<String> tokens) {
        return hapiTest(
                withOpContext((spec, ctxLog) -> {
                    List<SpecOperation> ops = new ArrayList<>();
                    tokens.forEach(token -> ops.add(tokenCreate(token)));
                    allRunFor(spec, ops);
                }),
                cryptoCreate("account").balance(ONE_HUNDRED_HBARS),
                sourcing(() ->
                        tokenAssociate("account", tokens).payingWith("account").via("associateTxn")),
                validateChargedUsd("associateTxn", TOKEN_ASSOCIATE_FEE * tokens.size()));
    }
}
