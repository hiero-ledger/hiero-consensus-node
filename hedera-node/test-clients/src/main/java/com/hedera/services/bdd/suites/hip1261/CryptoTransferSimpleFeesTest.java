// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHBARAndFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHbarFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNFTFullFeeUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoTransferSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String THRESHOLD_PAYER = "thresholdPayer";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";
    private static final String RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS = "receiverUnlimitedAutoAssociations";
    private static final String RECEIVER_FREE_AUTO_ASSOCIATIONS = "receiverFreeAutoAssociations";
    private static final String OWNER = "owner";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String NON_FUNGIBLE_TOKEN_2 = "nonFungibleToken2";
    private static final String NON_FUNGIBLE_TOKEN_3 = "nonFungibleToken3";
    private static final String adminKey = "adminKey";
    private static final String supplyKey = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private final double tokenAssociateFee = 0.05;


    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
        @DisplayName("Crypto Transfer Unassociated Accounts and Auto-Account Creation Positive Tests")
        class CryptoTransferUnassociatedAccountsAndAutoAccountCreationPositiveTests {
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
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
                                .via("ftTransferTxn"),
                        validateChargedUsdWithin("ftTransferTxn",
                                (expectedCryptoTransferFTAndNFTFullFeeUsd(
                                        2,
                                        0,
                                        3,
                                        1,
                                        2) + tokenAssociateFee * 3), 0.001)));
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
                                .via("ftTransferTxn")
                                .hasPrecheck(INVALID_SIGNATURE),

                        // assert no txn record is created
                        getTxnRecord("ftTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

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
                                .via("ftTransferTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),

                        // assert no txn record is created
                        getTxnRecord("ftTransferTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

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

        }

        @Nested
        @DisplayName("Crypto Transfer Unassociated and Auto-account Creation Negative Tests")
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

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_BILLION_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_THIRD).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(-1)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(2)
                        .balance(ONE_HBAR),
                newKeyNamed(adminKey),
                newKeyNamed(supplyKey)
        );
    }
}
