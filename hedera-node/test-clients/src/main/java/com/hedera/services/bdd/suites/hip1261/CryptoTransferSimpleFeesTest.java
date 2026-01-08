// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHbarFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoTransferSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String THRESHOLD_PAYER = "thresholdPayer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String PAYER_WITH_HOOK = "payerWithHook";
    private static final String PAYER_WITH_TWO_HOOKS = "payerWithTwoHooks";
    private static final String PAYER_WITH_THREE_HOOKS = "payerWithThreeHooks";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";
    private static final String RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS = "receiverUnlimitedAutoAssociations";
    private static final String RECEIVER_FREE_AUTO_ASSOCIATIONS = "receiverFreeAutoAssociations";
    private static final String RECEIVER_NOT_ASSOCIATED = "receiverNotAssociated";
    private static final String RECEIVER_ZERO_BALANCE = "receiverZeroBalance";
    private static final String VALID_ALIAS_ED25519 = "validAliasED25519";
    private static final String VALID_ALIAS_ECDSA = "validAliasECDSA";
    private static final String VALID_ALIAS_HOLLOW = "validAliasHollow";
    private static final String OWNER = "owner";
    private static final String HBAR_OWNER_INSUFFICIENT_BALANCE = "hbarOwnerInsufficientBalance";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String NON_FUNGIBLE_TOKEN_2 = "nonFungibleToken2";
    private static final String NON_FUNGIBLE_TOKEN_3 = "nonFungibleToken3";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";
    private static final String adminKey = "adminKey";
    private static final String supplyKey = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";
    private final double tokenAssociateFee = 0.05;


    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Crypto Transfer Simple Fees Positive Tests")
    class CryptoTransferSimpleFeesPositiveTests {

        // check base fees
        // check each extra
        // check combinations of extras
        // check hollow accounts
        // positive with unassociated account with free auto-association slots
        // positive with unassociated account with unlimited auto-association slots
        // negative with unassociated accounts including
        // negative with insufficient balance

        // Questions:
        // - Are NFT serials considered unique NFT tokens for counting purposes?
        // - I believe currently for cases with FT and NFT combinations, we do not charge both base fees ->
        // we need to implement the rest of the cases after the base fees fixes

        @Nested
        @DisplayName("Crypto Transfer HBAR Simple Fees Positive Tests")
        class CryptoTransferHBARSimpleFeesPositiveTests {
            @HapiTest
            @DisplayName("Crypto Transfer HBAR - base fees full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        1,
                                        0,
                                        1,
                                        0,
                                        0), 0.001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - extra signature full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_ExtraSignatureFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        2,
                                        0,
                                        1,
                                        0,
                                        0), 0.0001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - multiple movements, two unique accounts - base fees full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_MultipleMovementToSameAccountBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(2L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(3L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        0,
                                        0), 0.0001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - multiple movements, three unique accounts - accounts extras charging")
            final Stream<DynamicTest> cryptoTransferHBAR_ThreeUniqueAccountsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        2,
                                        0,
                                        3,
                                        0,
                                        0), 0.0001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - multiple movements, three unique accounts and sender is payer - " +
                    "accounts extra charging")
            final Stream<DynamicTest> cryptoTransferHBAR_ThreeUniqueAccountsAndSenderIsPayerCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        1,
                                        0,
                                        3,
                                        0,
                                        0), 0.001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - multiple movements, three unique accounts and sender with zero net change" +
                    " is not required to sign - accounts extra charging")
            final Stream<DynamicTest> cryptoTransferHBAR_ThreeUniqueAccountsAndSenderWithZeroNetChangeAccountsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(1L).between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        1,
                                        0,
                                        3,
                                        0,
                                        0), 0.001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - multiple movements, four unique accounts - accounts and signatures charging")
            final Stream<DynamicTest> cryptoTransferHBAR_FourUniqueAccountsAndExtraSignaturesCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(1L).between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER, RECEIVER_ASSOCIATED_SECOND)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        3,
                                        0,
                                        4,
                                        0,
                                        0), 0.0001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR - multiple movements to unique accounts extra fees full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_MultipleMovementsToUniqueAccountsExtraFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingHbar(2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                movingHbar(3L).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("hbarTransferTxn"),
                        validateChargedUsdWithin("hbarTransferTxn",
                                expectedCryptoTransferHbarFullFeeUsd(
                                        2,
                                        0,
                                        4,
                                        0,
                                        0), 0.001)));
            }
        }

        @Nested
        @DisplayName("Crypto Transfer Fungible Token Simple Fees Positive Tests")
        class CryptoTransferFungibleTokenSimpleFeesPositiveTests {
            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with one unique FT - base fees full charging")
            final Stream<DynamicTest> cryptoTransferOneUniqueFungibleTokenBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        1,
                                        0), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with one unique FT - extra signature full charging")
            final Stream<DynamicTest> cryptoTransferOneUniqueFungibleTokenExtraSignatureFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        1,
                                        0), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with two unique FT - extra FT charging")
            final Stream<DynamicTest> cryptoTransferTwoUniqueFungibleTokenBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN_2, 200L, OWNER, adminKey
                        ),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, FUNGIBLE_TOKEN_2),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(20L, FUNGIBLE_TOKEN_2).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        2,
                                        0), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                                .hasTokenBalance(FUNGIBLE_TOKEN_2, 180L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(FUNGIBLE_TOKEN_2, 20L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with two unique FT and three unique accounts - extra FT charging")
            final Stream<DynamicTest> cryptoTransferTwoUniqueFungibleTokensAndThreeUniqueAccountsBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN_2, 200L, OWNER, adminKey
                        ),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN_2),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(20L, FUNGIBLE_TOKEN_2).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        3,
                                        2,
                                        0), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                                .hasTokenBalance(FUNGIBLE_TOKEN_2, 180L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN_2, 20L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with two unique FT and four unique accounts - " +
                    "extra FT and accounts charging")
            final Stream<DynamicTest> cryptoTransferTwoUniqueFungibleTokensAndFourUniqueAccountsBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN_2, 200L, OWNER, adminKey
                        ),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN_2),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FUNGIBLE_TOKEN_2),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(20L, FUNGIBLE_TOKEN_2).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                moving(30L, FUNGIBLE_TOKEN_2).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        4,
                                        2,
                                        0), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                                .hasTokenBalance(FUNGIBLE_TOKEN_2, 150L),
                        getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FUNGIBLE_TOKEN_2, 30L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN_2, 20L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with similar FT movements base fees full charging")
            final Stream<DynamicTest> cryptoTransferTwoSimilarFungibleTokenMovementsBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        1,
                                        0), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 80L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 20L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with one unique FT and three unique accounts - " +
                    "account extras charging")
            final Stream<DynamicTest> cryptoTransferOneUniqueFungibleTokenAndThreeUniqueAccountsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        3,
                                        1,
                                        0), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 80L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Fungible Token - with one unique FT and four unique accounts - " +
                    "accounts extras charging")
            final Stream<DynamicTest> cryptoTransferOneUniqueFungibleTokenAndFourUniqueAccountsBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FUNGIBLE_TOKEN),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTFullFeeUsd(
                                        2,
                                        0,
                                        4,
                                        1,
                                        0), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 70L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }
        }

        @Nested
        @DisplayName("Crypto Transfer Non-Fungible Token Simple Fees Positive Tests")
        class CryptoTransferNonFungibleTokenSimpleFeesPositiveTests {
            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - with one serial base fees full charging")
            final Stream<DynamicTest> cryptoTransferNFTOneSerialBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        0,
                                        1), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - with one serial and extra signature full charging")
            final Stream<DynamicTest> cryptoTransferNFTOneSerialExtraSignatureFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        0,
                                        1), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - two unique NFT with one serial, one account - extras charging")
            final Stream<DynamicTest> cryptoTransferTwoUniqueNFTsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_2, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_2, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        0,
                                        2), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 3L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - three unique NFT with one serial, one account - extras charging")
            final Stream<DynamicTest> cryptoTransferThreeUniqueNFTsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_2, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_3, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2, NON_FUNGIBLE_TOKEN_3),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_2, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_3, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        0,
                                        3), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 3L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 3L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 1L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - three unique NFT with two serials, three unique accounts - " +
                    "tokens and accounts extras charging")
            final Stream<DynamicTest> cryptoTransferThreeUniqueNFTsToThreeUniqueAccountsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_2, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_3, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2, NON_FUNGIBLE_TOKEN_3),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2, NON_FUNGIBLE_TOKEN_3),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_2, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_3, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        3,
                                        0,
                                        6), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 2L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 2L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 1L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - three unique NFT with three serials, four unique accounts - " +
                    "max number of movements and extras charging")
            final Stream<DynamicTest> cryptoTransferThreeUniqueNFTsToFourUniqueAccountsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_2, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_3, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2, NON_FUNGIBLE_TOKEN_3),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2, NON_FUNGIBLE_TOKEN_3),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2, NON_FUNGIBLE_TOKEN_3),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_2, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_3, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                movingUnique(NON_FUNGIBLE_TOKEN, 3L).between(OWNER, RECEIVER_ASSOCIATED_THIRD),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 3L).between(OWNER, RECEIVER_ASSOCIATED_THIRD),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 3L).between(OWNER, RECEIVER_ASSOCIATED_THIRD),
                                movingUnique(NON_FUNGIBLE_TOKEN_3, 4L).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        4,
                                        0,
                                        10), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 0L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 1L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_3, 2L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - movement with two serials - extras charging")
            final Stream<DynamicTest> cryptoTransferNFTTwoSerialsMovementExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        0,
                                        2), 0.0001),
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer Non-Fungible Token - two unique NFT movements with two serials, two accounts - " +
                    "extras charging")
            final Stream<DynamicTest> cryptoTransferTwoUniqueNFTsMovementsWithTwoSerialsExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN_2, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN_2),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        mintNFT(NON_FUNGIBLE_TOKEN_2, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN_2, 1L, 2L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("nftTransferTxn"),
                        validateChargedUsdWithin("nftTransferTxn",
                                expectedCryptoTransferNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        0,
                                        4), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 2L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN_2, 2L)));
            }
        }

        @Nested
        @DisplayName("Crypto Transfer HBAR, FT and NFT Simple Fees Positive Tests")
        class CryptoTransferHBARAndFTAndNFTSimpleFeesPositiveTests {
            @HapiTest
            @DisplayName("Crypto Transfer FT and NFT - movements with one FT and one NFT serial - base fees full charging")
            final Stream<DynamicTest> cryptoTransferFTAndNFTOneSerialBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTAndNFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        1,
                                        1), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer FT and NFT - movements with one FT and one NFT serial - extra signature full charging")
            final Stream<DynamicTest> cryptoTransferFTAndNFTOneSerialExtraSignatureFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferFTAndNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        1,
                                        1), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR, FT and NFT - movements with one FT and one NFT serial - base fees full charging")
            final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTOneSerialBaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        1,
                                        1), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer HBAR, FT and NFT - movements with one FT and one NFT serial - extra signature full charging")
            final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTOneSerialExtraSignatureFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(PAYER)
                                .signedBy(OWNER, PAYER)
                                .fee(ONE_HBAR)
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        1,
                                        1), 0.0001),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }
        }

        @Nested
        @DisplayName("Crypto Transfer Unassociated Accounts, Auto-Account Creation and Transfers with Hooks Positive Tests")
        class CryptoTransferUnassociatedAccountsAndAutoAccountCreationAndHooksTransfersPositiveTests {
            @HapiTest
            @DisplayName("Crypto Transfer FT to Unassociated Account with unlimited Auto-associations - " +
                    "base fees full charging")
            final Stream<DynamicTest> cryptoTransferUnassociatedReceiverUnlimitedAutoAssociations_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),

                        // transfer tokens
                        cryptoTransfer(
                                moving(1L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .fee(ONE_HBAR)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        1,
                                        0) + tokenAssociateFee), 0.001)));
            }

            @HapiTest
            @DisplayName("Crypto Transfer FT to Unassociated Accounts with unlimited and free Auto-associations - " +
                    "base fees full charging")
            final Stream<DynamicTest>
            cryptoTransferUnassociatedReceiverUnlimitedAndFreeAutoAssociations_ExtrasCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(
                                NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                        // transfer tokens
                        cryptoTransfer(
                                moving(20L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS),
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L)
                                        .between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                moving(10L, FUNGIBLE_TOKEN)
                                        .between(RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .fee(ONE_HBAR)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferFTAndNFTFullFeeUsd(
                                        2,
                                        0,
                                        3,
                                        1,
                                        2) + tokenAssociateFee * 3), 0.001)));
            }

            // cases with unassociated accounts and mixed FT/NFT transfers

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create ED25519 Account with HBAR Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_ED25519_AutoAccountCreationForReceiver_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(10L).between(OWNER, VALID_ALIAS_ED25519))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferHbarFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        0,
                                        0)), 0.001),
                        // validate auto-created account properties
                        getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(-1))));
            }

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create ECDSA Account with HBAR Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_ECDSA_AutoAccountCreationForReceiver_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(10L).between(OWNER, VALID_ALIAS_ECDSA))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferHbarFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        0,
                                        0)), 0.001),
                        // validate auto-created account properties
                        getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA)
                                        .alias(VALID_ALIAS_ECDSA)
                                        .maxAutoAssociations(-1))));
            }

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create Hollow Account with HBAR Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferHBAR_HollowAutoAccountCreationForReceiver_BaseFeesFullCharging() {

                final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                        // transfer tokens
                        cryptoTransfer(
                                movingHbar(10L).between(OWNER, evmAlias.get()))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferHbarFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        0,
                                        0)), 0.001),
                        // validate auto-created account properties
                        getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .balance(10L)
                                        .maxAutoAssociations(-1))));
            }

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create ED25519 Account with FT Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferFT_ED25519_AutoAccountCreationForReceiver_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, VALID_ALIAS_ED25519))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        1,
                                        0) + tokenAssociateFee), 0.001),
                        // validate balances
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        // validate auto-created account properties
                        getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .hasToken(relationshipWith(FUNGIBLE_TOKEN))
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(-1))));
            }

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create ECDSA Account with FT Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferFT_ECDSA_AutoAccountCreationForReceiver_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(
                                FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        // transfer tokens
                        cryptoTransfer(
                                moving(10L, FUNGIBLE_TOKEN).between(OWNER, VALID_ALIAS_ECDSA))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        1,
                                        0) + tokenAssociateFee), 0.001),
                        // validate balances
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        // validate auto-created account properties
                        getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .hasToken(relationshipWith(FUNGIBLE_TOKEN))
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA)
                                        .alias(VALID_ALIAS_ECDSA)
                                        .maxAutoAssociations(-1))));
            }

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create ED25519 Account with NFT Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferNFT_ED25519_AutoAccountCreationForReceiver_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, VALID_ALIAS_ED25519))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferNFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        0,
                                        1) + tokenAssociateFee), 0.001),
                        // validate balances
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                        // validate auto-created account properties
                        getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .hasToken(relationshipWith(NON_FUNGIBLE_TOKEN))
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(-1))));
            }

            @HapiTest
            @DisplayName("Crypto Transfer - Auto Create ECDSA Account with NFT Transfer - base fees full charging")
            final Stream<DynamicTest> cryptoTransferNFT_ECDSA_AutoAccountCreationForReceiver_BaseFeesFullCharging() {
                return hapiTest(flattened(
                        // create keys, tokens and accounts
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        // transfer tokens
                        cryptoTransfer(
                                movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, VALID_ALIAS_ECDSA))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenTransferTxn"),
                        validateChargedUsdWithin("tokenTransferTxn",
                                (expectedCryptoTransferNFTFullFeeUsd(
                                        1,
                                        0,
                                        2,
                                        0,
                                        1) + tokenAssociateFee), 0.001),
                        // validate balances
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                        // validate auto-created account properties
                        getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .hasToken(relationshipWith(NON_FUNGIBLE_TOKEN))
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA)
                                        .alias(VALID_ALIAS_ECDSA)
                                        .maxAutoAssociations(-1))));
            }

            // mixed cases - auto-accounts creation and auto-associaions with FT/NFT transfers - all movings in one crypto transfer

            @Nested
            @DisplayName("Crypto Transfer With Hooks - Simple Fees Positive Tests")
            class CryptoTransferWithHooksSimpleFeesPositiveTests {
                @HapiTest
                @DisplayName("Crypto Transfer HBAR with hook execution - extra hook full charging")
                final Stream<DynamicTest> cryptoTransferHBARWithOneHookExtraHookFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST))
                                    .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("hbarTransferTxn"),
                            validateChargedUsdWithin("hbarTransferTxn",
                                    expectedCryptoTransferHbarFullFeeUsd(
                                            1,
                                            1,
                                            2,
                                            0,
                                            0), 0.0001)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer FT with hook execution - extra hook full charging")
                final Stream<DynamicTest> cryptoTransferFTWithOneHookExtraHookFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, PAYER_WITH_HOOK, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                            // transfer tokens
                            cryptoTransfer(
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST))
                                    .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("ftTransferTxn"),
                            validateChargedUsdWithin("ftTransferTxn",
                                    expectedCryptoTransferFTFullFeeUsd(
                                            1,
                                            1,
                                            2,
                                            1,
                                            0), 0.0001),
                            getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer NFT with hook execution - extra hook full charging")
                final Stream<DynamicTest> cryptoTransferNFTWithOneHookExtraHookFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_HOOK, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST))
                                    .withNftSenderPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("nftTransferTxn"),
                            validateChargedUsdWithin("nftTransferTxn",
                                    expectedCryptoTransferNFTFullFeeUsd(
                                            1,
                                            1,
                                            2,
                                            0,
                                            1), 0.0001),
                            getAccountBalance(PAYER_WITH_HOOK).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR and FT with hook execution - extra hooks full charging")
                final Stream<DynamicTest> cryptoTransferHBARAndFtWithTwoHooksExtraHookFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, PAYER_WITH_TWO_HOOKS, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST))
                                    .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000_000L, "")
                                    .withPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("tokenTransferTxn"),
                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTFullFeeUsd(
                                            1,
                                            2,
                                            3,
                                            1,
                                            0), 0.0001),
                            getAccountBalance(PAYER_WITH_TWO_HOOKS).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR and NFT with hook execution - extra hooks full charging")
                final Stream<DynamicTest> cryptoTransferHBARAndNFtWithOneHookExtraHookFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_FIRST))
                                    .withPreHookFor(PAYER_WITH_HOOK, 1L, 5_000_000_000L, "")
                                    .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("tokenTransferTxn"),
                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndNFTFullFeeUsd(
                                            1,
                                            2,
                                            3,
                                            0,
                                            1), 0.0001),
                            getAccountBalance(PAYER_WITH_TWO_HOOKS).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT with hook execution - extra hooks and accounts full charging")
                final Stream<DynamicTest> cryptoTransferHBARAndFtAndNFTWithTwoHooksExtraHooksAndAccountsFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, PAYER_WITH_TWO_HOOKS, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD))
                                    .withPreHookFor(PAYER_WITH_TWO_HOOKS, 1L, 5_000_000_000L, "")
                                    .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("tokenTransferTxn"),
                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                            1,
                                            2,
                                            5,
                                            1,
                                            1), 0.0001),
                            getAccountBalance(PAYER_WITH_TWO_HOOKS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                            getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                            getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT with hook execution - extra hooks, tokens and accounts full charging")
                final Stream<DynamicTest> cryptoTransferHBARAndFtAndNFTWithTwoHooksExtraHooksAndTokensAndAccountsFullCharging() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, PAYER_WITH_TWO_HOOKS, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, PAYER_WITH_TWO_HOOKS, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(PAYER_WITH_HOOK, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_SECOND),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(PAYER_WITH_TWO_HOOKS, RECEIVER_ASSOCIATED_THIRD))
                                    .withPreHookFor(PAYER_WITH_TWO_HOOKS, 1L, 5_000_000_000L, "")
                                    .withNftSenderPreHookFor(PAYER_WITH_TWO_HOOKS, 2L, 5_000_000_000L, "")
                                    .payingWith(PAYER_WITH_HOOK)
                                    .signedBy(PAYER_WITH_HOOK, PAYER_WITH_TWO_HOOKS)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .via("tokenTransferTxn"),
                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                            1,
                                            2,
                                            5,
                                            1,
                                            1), 0.0001),
                            getAccountBalance(PAYER_WITH_TWO_HOOKS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                            getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                            getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
                }
            }
        }

        @Nested
        @DisplayName("Crypto Transfer Simple Fees Negative Tests")
        class CryptoTransferSimpleFeesNegativeTests {
            @Nested
            @DisplayName("Crypto Transfer Simple Fees Failures on Ingest")
            class CryptoTransferSimpleFeesFailuresOnIngest {
                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with invalid signature - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithInvalidSignatureFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();

                    // Define a threshold submit key that requires two simple keys signatures
                    KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                    // Create invalid signature with both simple keys signing
                    SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            newKeyNamed(PAYER_KEY).shape(keyShape),
                            cryptoCreate(THRESHOLD_PAYER)
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, invalidSig))
                                    .balance(ONE_HUNDRED_HBARS),
                            getAccountBalance(THRESHOLD_PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, THRESHOLD_PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(INVALID_SIGNATURE),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(THRESHOLD_PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with insufficient txn fee - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithInsufficientTxnFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR / 1000) // insufficient fee
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(INSUFFICIENT_TX_FEE),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with insufficient payer balance - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithInsufficientPayerBalanceFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER_INSUFFICIENT_BALANCE).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER_INSUFFICIENT_BALANCE)
                                    .signedBy(OWNER, PAYER_INSUFFICIENT_BALANCE)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER_INSUFFICIENT_BALANCE).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with too long memo - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithTooLongMemoFailsOnIngest() {
                    final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .memo(LONG_MEMO)
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(MEMO_TOO_LONG),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - expired transaction - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTExpiredTxnFailsOnIngest() {
                    final var expiredTxnId = "expiredTxn";
                    final var oneHourPast = -3_600L; // 1 hour before
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            usableTxnIdNamed(expiredTxnId)
                                    .modifyValidStart(oneHourPast)
                                    .payerId(PAYER),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(expiredTxnId)
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(TRANSACTION_EXPIRED),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with too far start time - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTTooFarStartTimeFailsOnIngest() {
                    final var invalidTxnStartId = "invalidTxnStart";
                    final var oneHourPast = 3_600L; // 1 hour later
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            usableTxnIdNamed(invalidTxnStartId)
                                    .modifyValidStart(oneHourPast)
                                    .payerId(PAYER),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId(invalidTxnStartId)
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(INVALID_TRANSACTION_START),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with invalid duration time - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithInvalidDurationTimeFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .validDurationSecs(0) // invalid duration time
                                    .via("tokenTransferTxn")
                                    .hasPrecheck(INVALID_TRANSACTION_DURATION),

                            // assert no txn record is created
                            getTxnRecord("tokenTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - duplicate txn - fails on ingest")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTDuplicateTxnFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // initial transaction
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .fee(ONE_HBAR)
                                    .via("initialTokenTransferTxn"),
                            // duplicate transaction
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .txnId("initialTokenTransferTxn")
                                    .via("duplicateTokenTransferTxn")
                                    .hasPrecheck(DUPLICATE_TRANSACTION),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }
            }

            @Nested
            @DisplayName("Crypto Transfer Simple Fees Failures on Pre-Handle")
            class CryptoTransferSimpleFeesFailuresOnPreHandle {

            }

            @Nested
            @DisplayName("Crypto Transfer Simple Fees Failures on Handle")
            class CryptoTransferSimpleFeesFailuresOnHandle {
                @Tag(ONLY_SUBPROCESS)
                @LeakyHapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - duplicate txn - fails on handle")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTDuplicateTxnFailsOnHandle() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    final AtomicLong initialNodeBalance = new AtomicLong();
                    final AtomicLong afterNodeBalance = new AtomicLong();

                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                            getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // Register a TxnId for the inner txn
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),

                            // initial transaction
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .setNode(4)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .via("initialTokenTransferTxn")
                                    .logged(),
                            // duplicate transaction
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .setNode(3)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .via("duplicateTokenTransferTxn")
                                    .hasPrecheck(DUPLICATE_TRANSACTION),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                            withOpContext((spec, log) -> {
                                long payerDelta = initialBalance.get() - afterBalance.get();
                                log.info("Payer balance changed by {} tinybars", payerDelta);
                                log.info("Recorded fee: {}", expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                        2,
                                        0,
                                        2,
                                        1,
                                        1
                                ));
                                assertEquals(initialNodeBalance.get(), afterNodeBalance.get());
                                assertTrue(initialBalance.get() > afterBalance.get());
                            }),
                            validateChargedFeeToUsd(
                                    "initialTokenTransferTxn",
                                    initialBalance,
                                    afterBalance,
                                    expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            1,
                                            1),
                                    0.001)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR - with insufficient token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferWithInsufficientHBARTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(ONE_MILLION_HBARS).between(HBAR_OWNER_INSUFFICIENT_BALANCE, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(HBAR_OWNER_INSUFFICIENT_BALANCE, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHbarFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            0,
                                            0), 0.0001),
                            getAccountBalance(HBAR_OWNER_INSUFFICIENT_BALANCE).hasTinyBars(1000L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTinyBars(100000000L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer FT - with insufficient FT token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferWithInsufficientFTTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 10L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                            // transfer tokens
                            cryptoTransfer(
                                    moving(20L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            1,
                                            0), 0.0001),
                            getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer NFT - with insufficient FT token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferWithInsufficientNFTTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),

                            // transfer tokens
                            cryptoTransfer(
                                    movingUnique(NON_FUNGIBLE_TOKEN, 5L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INVALID_NFT_ID),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferNFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            0,
                                            1), 0.0001),
                            getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR and FT - with insufficient FT token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferHBARAndFTWithInsufficientFTTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 10L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(20L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            1,
                                            0), 0.0001),
                            getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR and NFT - with insufficient FT token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferHBARAndNFTWithInsufficientNFTTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 5L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INVALID_NFT_ID),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndNFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            0,
                                            1), 0.0001),
                            getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with insufficient FT token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithInsufficientFTTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 10L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(20L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .memo("Testing insufficient FT token balance")
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            1,
                                            1), 0.0001),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                    ));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - with insufficient NFT token balance - fails on handle")
                final Stream<DynamicTest> cryptoTransferHBARAndFTAndNFTWithInsufficientNFTTokenBalanceFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(20L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 5L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(INVALID_NFT_ID),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            1,
                                            1), 0.0001),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 100L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer FT - receiver not associated to token - fails on handle")
                final Stream<DynamicTest> cryptoTransferFTReceiverNotAssociatedToTokenFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 10L, OWNER, adminKey),

                            // transfer tokens
                            cryptoTransfer(
                                    moving(20L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_NOT_ASSOCIATED))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            1,
                                            0), 0.0001),
                            getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                            getAccountBalance(RECEIVER_NOT_ASSOCIATED).hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer NFT - receiver not associated to token - fails on handle")
                final Stream<DynamicTest> cryptoTransferNFTReceiverNotAssociatedToTokenFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_NOT_ASSOCIATED))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferNFTFullFeeUsd(
                                            2,
                                            0,
                                            2,
                                            0,
                                            1), 0.0001),
                            getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_NOT_ASSOCIATED).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer FT and NFT - receiver not associated to token - fails on handle")
                final Stream<DynamicTest> cryptoTransferFTAndNFTReceiverNotAssociatedToTokenFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 20L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(5L, FUNGIBLE_TOKEN).between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_NOT_ASSOCIATED),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_NOT_ASSOCIATED))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferFTAndNFTFullFeeUsd(
                                            2,
                                            0,
                                            3,
                                            1,
                                            1), 0.0001),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 20L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                            getAccountBalance(RECEIVER_NOT_ASSOCIATED)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Crypto Transfer HBAR, FT and NFT - receiver not associated to token - fails on handle")
                final Stream<DynamicTest> cryptoTransferHBARFTAndNFTReceiverNotAssociatedToTokenFailsOnIngest() {
                    return hapiTest(flattened(
                            // create keys, tokens and accounts
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(
                                    FUNGIBLE_TOKEN, 20L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            createNonFungibleTokenWithoutCustomFees(
                                    NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),

                            // transfer tokens
                            cryptoTransfer(
                                    movingHbar(1L).between(OWNER, RECEIVER_ZERO_BALANCE),
                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                    moving(5L, FUNGIBLE_TOKEN).between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_NOT_ASSOCIATED),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_NOT_ASSOCIATED))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .fee(ONE_HBAR)
                                    .via("tokenTransferTxn")
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),

                            validateChargedUsdWithin("tokenTransferTxn",
                                    expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd(
                                            2,
                                            0,
                                            4,
                                            1,
                                            1), 0.0001),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 20L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_ZERO_BALANCE).hasTinyBars(0L),
                            getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                            getAccountBalance(RECEIVER_NOT_ASSOCIATED)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }
            }

            @Nested
            @DisplayName("Crypto Transfer Auto-account Creation Negative Tests")
            class CryptoTransferUnassociatedAndAutoAccountCreationNegativeTests {

            }

        }

        private HapiTokenCreate createFungibleTokenWithoutCustomFees(
                String tokenName, long supply, String treasury, String adminKey) {
            return tokenCreate(tokenName)
                    .initialSupply(supply)
                    .treasury(treasury)
                    .adminKey(adminKey)
                    .tokenType(FUNGIBLE_COMMON);
        }

        private HapiTokenCreate createNonFungibleTokenWithoutCustomFees(
                String tokenName, String treasury, String supplyKey, String adminKey) {
            return tokenCreate(tokenName)
                    .initialSupply(0)
                    .treasury(treasury)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .supplyKey(supplyKey)
                    .adminKey(adminKey);
        }

        private HapiTokenMint mintNFT(String tokenName, int rangeStart, int rangeEnd) {
            return mintToken(
                    tokenName,
                    IntStream.range(rangeStart, rangeEnd)
                            .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                            .toList());
        }

        private SpecOperation registerEvmAddressAliasFrom(String secp256k1KeyName, AtomicReference<ByteString> evmAlias) {
            return withOpContext((spec, opLog) -> {
                final var ecdsaKey =
                        spec.registry().getKey(secp256k1KeyName).getECDSASecp256K1().toByteArray();
                final var evmAddressBytes = recoverAddressFromPubKey(Bytes.wrap(ecdsaKey));
                final var evmAddress = ByteString.copyFrom(evmAddressBytes.toByteArray());
                evmAlias.set(evmAddress);
            });
        }

        private List<SpecOperation> createAccountsAndKeys() {
            return List.of(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(ONE_HBAR / 100000),
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000),
                    cryptoCreate(PAYER_WITH_HOOK).balance(ONE_HUNDRED_HBARS)
                            .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                    cryptoCreate(PAYER_WITH_TWO_HOOKS).balance(ONE_HUNDRED_HBARS)
                            .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
                    cryptoCreate(PAYER_WITH_THREE_HOOKS).balance(ONE_HUNDRED_HBARS)
                            .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .withHook(accountAllowanceHook(2L, HOOK_CONTRACT))
                            .withHook(accountAllowanceHook(3L, HOOK_CONTRACT)),
                    cryptoCreate(OWNER).balance(ONE_BILLION_HBARS),
                    cryptoCreate(HBAR_OWNER_INSUFFICIENT_BALANCE).balance(ONE_HBAR / 100000),
                    cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                    cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                    cryptoCreate(RECEIVER_ASSOCIATED_THIRD).balance(ONE_HBAR),
                    cryptoCreate(RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS)
                            .maxAutomaticTokenAssociations(-1)
                            .balance(ONE_HBAR),
                    cryptoCreate(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                            .maxAutomaticTokenAssociations(2)
                            .balance(ONE_HBAR),
                    cryptoCreate(RECEIVER_NOT_ASSOCIATED).balance(ONE_HBAR),
                    cryptoCreate(RECEIVER_ZERO_BALANCE).balance(0L),
                    newKeyNamed(VALID_ALIAS_ED25519).shape(KeyShape.ED25519),
                    newKeyNamed(VALID_ALIAS_ECDSA).shape(SECP_256K1_SHAPE),
                    newKeyNamed(VALID_ALIAS_HOLLOW).shape(SECP_256K1_SHAPE),
                    newKeyNamed(adminKey),
                    newKeyNamed(supplyKey)
            );
        }
    }
}
