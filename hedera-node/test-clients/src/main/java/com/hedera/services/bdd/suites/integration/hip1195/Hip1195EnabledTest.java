// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration.hip1195;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_IN_USE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(12)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(CONCURRENT)
public class Hip1195EnabledTest {
    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @Contract(contract = "SmartContractsFees")
    static SpecContract HOOK_UPDATE_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(HOOK_CONTRACT.getInfo());
        testLifecycle.doAdhoc(HOOK_UPDATE_CONTRACT.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarDebitPreOnlyHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPreOnlyHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarDebitPrePostHook() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> accessingWrongHookFails() {
        return hapiTest(
                cryptoCreate("accountWithDifferentHooks").withHooks(accountAllowanceHook(125L, HOOK_CONTRACT.name())),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 125L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("accountWithDifferentHooks", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPrePostHookReceiverSigRequired() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeHbarCreditPreOnlyHookReceiverSigRequired() {
        return hapiTest(
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenDebitPreOnlyHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                tokenCreate("token")
                        .treasury("testAccount")
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenWithCustomFeesDebitPreOnlyHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                tokenCreate("token")
                        .treasury("testAccount")
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .withCustom(fixedHbarFee(1L, GENESIS))
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .via("customFeeTxn"),
                getTxnRecord("customFeeTxn").logged());
    }

    //    @HapiTest
    final Stream<DynamicTest> royaltyAndFractionalTogetherCaseStudy() {
        final var alice = "alice";
        final var amelie = "AMELIE";
        final var usdcTreasury = "bank";
        final var usdcCollector = "usdcFees";
        final var westWindTreasury = "COLLECTION";
        final var westWindArt = "WEST_WIND_ART";
        final var usdc = "USDC";
        final var supplyKey = "SUPPLY";

        final var txnFromTreasury = "TXN_FROM_TREASURY";
        final var txnFromAmelie = "txnFromAmelie";

        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate(alice)
                        .withHooks(accountAllowanceHook(123L, HOOK_CONTRACT.name()))
                        .balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(amelie).withHooks(accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                cryptoCreate(usdcTreasury),
                cryptoCreate(usdcCollector),
                cryptoCreate(westWindTreasury),
                cryptoCreate("receiverUsdc").maxAutomaticTokenAssociations(1),
                tokenCreate(usdc)
                        .signedBy(DEFAULT_PAYER, usdcTreasury, usdcCollector)
                        .initialSupply(Long.MAX_VALUE)
                        .withCustom(fractionalFee(1, 2, 0, OptionalLong.empty(), usdcCollector))
                        .treasury(usdcTreasury),
                tokenAssociate(westWindTreasury, usdc),
                tokenCreate(westWindArt)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(supplyKey)
                        .treasury(westWindTreasury)
                        .withCustom(royaltyFeeWithFallback(
                                1, 100, fixedHtsFeeInheritingRoyaltyCollector(1, usdc), westWindTreasury)),
                tokenAssociate(amelie, List.of(westWindArt, usdc)),
                tokenAssociate(alice, List.of(westWindArt, usdc)),
                mintToken(westWindArt, List.of(copyFromUtf8("test"))),
                cryptoTransfer(moving(200, usdc).between(usdcTreasury, alice)).fee(ONE_HBAR),
                cryptoTransfer(movingUnique(westWindArt, 1L).between(westWindTreasury, amelie))
                        .fee(ONE_HBAR)
                        .via(txnFromTreasury),
                cryptoTransfer(
                                movingUnique(westWindArt, 1L).between(amelie, alice),
                                moving(200, usdc).distributing(alice, amelie, "receiverUsdc"),
                                movingHbar(10 * ONE_HUNDRED_HBARS).between(alice, amelie))
                        .withPreHookFor("AMELIE", 124L, 25_000L, "")
                        .signedBy(amelie, alice)
                        .payingWith(amelie)
                        .via(txnFromAmelie)
                        .fee(ONE_HBAR),
                getTxnRecord(txnFromAmelie).logged());
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenDebitPrePostHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                tokenCreate("token")
                        .treasury("testAccount")
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingHbar(10).between("testAccount", GENESIS))
                        .withPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenCreditPrePostHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                tokenAssociate("testAccount", "token"),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .withPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .withPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeTokenCreditPreOnlyHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                mintToken("token", 10),
                tokenAssociate("testAccount", "token"),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftDebitSenderPreHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                tokenCreate("token")
                        .treasury("testAccount")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate(GENESIS, "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between("testAccount", GENESIS))
                        .withNftSenderPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between("testAccount", GENESIS))
                        .withNftSenderPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftDebitSenderPrePostHook() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name())),
                tokenCreate("token")
                        .treasury("testAccount")
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate(GENESIS, "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between("testAccount", GENESIS))
                        .withNftSenderPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between("testAccount", GENESIS))
                        .withNftSenderPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftCreditReceiverPreHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate("testAccount", "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .withNftSenderPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .withNftReceiverPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .withNftReceiverPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftCreditReceiverPrePostHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .maxSupply(1000L),
                mintToken(
                        "token",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAssociate("testAccount", "token"),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .withNftSenderPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .withNftReceiverPrePostHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.movingUnique("token", 1L).between(GENESIS, "testAccount"))
                        .withNftReceiverPrePostHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> authorizeNftCreditPreOnlyHookReceiverSigRequired() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate("testAccount")
                        .withHooks(
                                accountAllowanceHook(123L, HOOK_CONTRACT.name()),
                                accountAllowanceHook(124L, HOOK_CONTRACT.name()))
                        .receiverSigRequired(true),
                tokenCreate("token")
                        .treasury(GENESIS)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .initialSupply(10L)
                        .maxSupply(1000L),
                tokenAssociate("testAccount", "token"),
                mintToken("token", 10),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 123L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK),
                cryptoTransfer(TokenMovement.moving(10, "token").between(GENESIS, "testAccount"))
                        .withPreHookFor("testAccount", 124L, 25_000L, "")
                        .signedBy(DEFAULT_PAYER));
    }

    @HapiTest
    final Stream<DynamicTest> duplicateHookIdsInOneListFailsPrecheck() {
        final var OWNER = "acctDupIds";
        final var H1 = accountAllowanceHook(7L, HOOK_CONTRACT.name());
        final var H2 = accountAllowanceHook(7L, HOOK_CONTRACT.name());

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHook(H1)
                        .withHook(H2)
                        .hasPrecheck(HOOK_ID_REPEATED_IN_CREATION_DETAILS));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooks() {
        final var OWNER = "acctHeadRun";
        final long A = 1L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER).key("k").balance(1L).withHooks(accountAllowanceHook(A, HOOK_CONTRACT.name())),
                cryptoUpdate(OWNER).removingHooks(A));
    }

    @HapiTest
    final Stream<DynamicTest> deleteHooksAndLinkNewOnes() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L, E = 5L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(A, HOOK_CONTRACT.name()),
                                accountAllowanceHook(B, HOOK_CONTRACT.name()),
                                accountAllowanceHook(C, HOOK_CONTRACT.name())),
                cryptoUpdate(OWNER)
                        .withHooks(
                                accountAllowanceHook(A, HOOK_CONTRACT.name()),
                                accountAllowanceHook(E, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_ID_IN_USE),
                // Delete A,B (at head) and add D,E. Head should become D (the first in the creation list)
                cryptoUpdate(OWNER)
                        .removingHooks(A, B)
                        .withHooks(
                                accountAllowanceHook(D, HOOK_CONTRACT.name()),
                                accountAllowanceHook(E, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(D, a.firstHookId());
                    // started with 3; minus 2 deletes; plus 2 adds -> 3 again
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(accountAllowanceHook(A, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).removingHooks(D).withHooks(accountAllowanceHook(D, HOOK_CONTRACT.name())));
    }

    @HapiTest
    final Stream<DynamicTest> deleteAllHooks() {
        final var OWNER = "acctHeadRun";
        final long A = 1L, B = 2L, C = 3L, D = 4L;

        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER)
                        .key("k")
                        .balance(1L)
                        .withHooks(
                                accountAllowanceHook(A, HOOK_CONTRACT.name()),
                                accountAllowanceHook(B, HOOK_CONTRACT.name()),
                                accountAllowanceHook(C, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(A, a.firstHookId());
                    assertEquals(3, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER).removingHooks(A, B, C),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(0L, a.firstHookId());
                    assertEquals(0, a.numberHooksInUse());
                }),
                cryptoUpdate(OWNER)
                        .removingHooks(A)
                        .withHooks(accountAllowanceHook(A, HOOK_CONTRACT.name()))
                        .hasKnownStatus(HOOK_NOT_FOUND),
                cryptoUpdate(OWNER).withHooks(accountAllowanceHook(D, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(4L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteHookWithStorage() {
        final var OWNER = "acctHeadRun";
        final Bytes A = Bytes.wrap("a");
        final Bytes B = Bytes.wrap("Bb");
        final Bytes C = Bytes.wrap("cCc");
        final Bytes D = Bytes.fromHex("dddd");
        return hapiTest(
                newKeyNamed("k"),
                cryptoCreate(OWNER).key("k").balance(1L).withHooks(accountAllowanceHook(1L, HOOK_CONTRACT.name())),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(0, a.numberLambdaStorageSlots());
                }),
                accountLambdaSStore(OWNER, 1L).putSlot(A, B).putSlot(C, D),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberLambdaStorageSlots());
                }),
                cryptoUpdate(OWNER).removingHooks(1L).hasKnownStatus(HOOK_DELETION_REQUIRES_ZERO_STORAGE_SLOTS),
                viewAccount(OWNER, (Account a) -> {
                    assertEquals(1L, a.firstHookId());
                    assertEquals(1, a.numberHooksInUse());
                    assertEquals(2, a.numberLambdaStorageSlots());
                }));
    }
}
