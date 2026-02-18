// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedSimpleFees;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
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
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_UPDATE_NFT_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
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
    private static final int NETWORK_MULTIPLIER = 9;
    /**
     * Simple fees formula for token ops:
     * node    = NODE_BASE + SIGNATURE_FEE * extraSignatures + bytesOverage * SINGLE_BYTE_FEE
     * network = node * NETWORK_MULTIPLIER
     * service = serviceBaseUsd
     * total   = node + network + service
     */
    private static double simpleTokenOpFeeUsd(
            final double serviceBaseUsd, final long extraSignatures, final int signedTxnSize) {
        final double nodeFeeUsd =
                NODE_BASE_FEE_USD + (extraSignatures * SIGNATURE_FEE_USD) + nodeFeeFromBytesUsd(signedTxnSize);
        return serviceBaseUsd + nodeFeeUsd * (NETWORK_MULTIPLIER + 1);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate create fungible token simple fees")
    final Stream<DynamicTest> validateCreateFungibleToken() {
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("create-token-txn"),
                validateChargedSimpleFees("Simple Fees", "create-token-txn", 1.0, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate create non-fungible token simple fees")
    final Stream<DynamicTest> validateCreateNonFungibleToken() {
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                tokenCreate(NFT_TOKEN)
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
                        .via("create-nft-txn"),
                validateChargedSimpleFees("Simple Fees", "create-nft-txn", 1.0, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate create fungible token with custom fees simple fees")
    final Stream<DynamicTest> validateCreateFungibleTokenWithCustomFees() {
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(HBAR_COLLECTOR).balance(0L),
                tokenCreate("tokenWithCustomFees")
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
                        .via("create-token-custom-fees-txn"),
                // TOKEN_CREATE_BASE_FEE_USD (0.9999) + TOKEN_CREATE_WITH_CUSTOM_FEES_FEE_USD (1.0) = ~2.0
                validateChargedSimpleFees("Simple Fees", "create-token-custom-fees-txn", 2.0, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate update fungible token simple fees")
    final Stream<DynamicTest> validateUpdateFungibleToken() {
        final var newSupplyKey = "newSupplyKey";
        // Extra signatures: payer + old supply key + new supply key (node includes 1 signature)
        final var extraSignatures = 2L;

        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(newSupplyKey),
                cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .payingWith(PAYER)
                        .fee(ONE_MILLION_HBARS)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(FUNGIBLE_COMMON)
                        .hasKnownStatus(SUCCESS),
                tokenUpdate(FUNGIBLE_TOKEN)
                        .payingWith(PAYER)
                        .fee(ONE_MILLION_HBARS)
                        .signedBy(PAYER, SUPPLY_KEY, newSupplyKey)
                        .supplyKey(newSupplyKey)
                        .hasKnownStatus(SUCCESS)
                        .via("update-token-txn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "update-token-txn");
                    final var expectedFee =
                            simpleTokenOpFeeUsd(TOKEN_UPDATE_BASE_FEE_USD, extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "update-token-txn", expectedFee, 1));
                }));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate mint common token simple fees")
    final Stream<DynamicTest> validateMintCommonToken() {
        // TOKEN_MINT_BASE_FEE_USD (0.0009) ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SUPPLY_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .payingWith(PAYER)
                        .supplyKey(SUPPLY_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS),
                mintToken(FUNGIBLE_TOKEN, 1)
                        .payingWith(PAYER)
                        .signedBy(SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("fungible-mint-txn"),
                validateChargedSimpleFees("Simple Fees", "fungible-mint-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate mint multiple common tokens simple fees")
    final Stream<DynamicTest> validateMintMultipleCommonToken() {
        // TOKEN_MINT_BASE_FEE_USD (0.0009) ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN).balance(ONE_MILLION_HBARS),
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
                        .signedBy(SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("fungible-mint-txn"),
                validateChargedSimpleFees("Simple Fees", "fungible-mint-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate mint a unique token simple fees")
    final Stream<DynamicTest> validateMintUniqueToken() {
        // TOKEN_MINT_BASE_FEE_USD (0.0009) = 0.0209
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .hasKnownStatus(SUCCESS),
                mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("Bart Simpson")))
                        .payingWith(PAYER)
                        .signedBy(SUPPLY_KEY)
                        .blankMemo()
                        .fee(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS)
                        .via("nft-mint-txn"),
                validateChargedSimpleFees("Simple Fees", "nft-mint-txn", 0.02, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate mint multiple unique tokens simple fees")
    final Stream<DynamicTest> validateMintMultipleUniqueToken() {
        // TOKEN_MINT_BASE_FEE_USD (0.019) + TOKEN_MINT_NFT_FEE_USD (0.02) * 2 = 0.06
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .hasKnownStatus(SUCCESS),
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
                        .via("nft-multiple-mint-txn"),
                validateChargedSimpleFees("Simple Fees", "nft-multiple-mint-txn", 0.06, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare pause a common token")
    final Stream<DynamicTest> comparePauseToken() {
        // Extra signatures: payer + pause key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("pause-token-txn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "pause-token-txn");
                    final var expectedFee =
                            simpleTokenOpFeeUsd(TOKEN_PAUSE_BASE_FEE_USD, extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "pause-token-txn", expectedFee, 1));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare unpause a common token")
    final Stream<DynamicTest> compareUnpauseToken() {
        // Extra signatures: payer + pause key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("unpause-token-txn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "unpause-token-txn");
                    final var expectedFee =
                            simpleTokenOpFeeUsd(TOKEN_UNPAUSE_BASE_FEE_USD, extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "unpause-token-txn", expectedFee, 1));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare freeze a common token")
    final Stream<DynamicTest> compareFreezeToken() {
        // Extra signatures: payer + freeze key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("freeze-token-txn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "freeze-token-txn");
                    final var expectedFee =
                            simpleTokenOpFeeUsd(TOKEN_FREEZE_BASE_FEE_USD, extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "freeze-token-txn", expectedFee, 1));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare unfreeze a common token")
    final Stream<DynamicTest> compareUnfreezeToken() {
        // Extra signatures: payer + freeze key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("unfreeze-token-txn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "unfreeze-token-txn");
                    final var expectedFee =
                            simpleTokenOpFeeUsd(TOKEN_UNFREEZE_BASE_FEE_USD, extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "unfreeze-token-txn", expectedFee, 1));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate burn a common token simple fees")
    final Stream<DynamicTest> validateBurnToken() {
        // TOKEN_BURN_BASE_FEE_USD (0.0009) ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("burn-token-txn"),
                validateChargedSimpleFees("Simple Fees", "burn-token-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare delete a common token")
    final Stream<DynamicTest> compareDeleteToken() {
        // Extra signatures: payer + admin key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("delete-token-txn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "delete-token-txn");
                    final var expectedFee =
                            simpleTokenOpFeeUsd(TOKEN_DELETE_BASE_FEE_USD, extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "delete-token-txn", expectedFee, 1));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate associate a token simple fees")
    final Stream<DynamicTest> validateAssociateToken() {
        // TOKEN_ASSOCIATE_BASE_FEE_USD (0.0499) ≈ 0.05
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("token-associate-txn"),
                validateChargedSimpleFees("Simple Fees", "token-associate-txn", 0.05, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate dissociate a token simple fees")
    final Stream<DynamicTest> validateDissociateToken() {
        // TOKEN_DISSOCIATE_BASE_FEE_USD = 0.0499 ≈ 0.05
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                tokenDissociate(OTHER, FUNGIBLE_TOKEN).payingWith(OTHER).via("dissociate-txn"),
                validateChargedSimpleFees("Simple Fees", "dissociate-txn", 0.05, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate grant kyc simple fees")
    final Stream<DynamicTest> validateGrantKyc() {
        // TOKEN_GRANT_KYC_BASE_FEE_USD = 0.0009 ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("grant-kyc-txn"),
                validateChargedSimpleFees("Simple Fees", "grant-kyc-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate revoke kyc simple fees")
    final Stream<DynamicTest> validateRevokeKyc() {
        // TOKEN_REVOKE_KYC_BASE_FEE_USD = 0.0009 ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                grantTokenKyc(FUNGIBLE_TOKEN, OTHER).fee(ONE_HUNDRED_HBARS).payingWith(OTHER),
                revokeTokenKyc(FUNGIBLE_TOKEN, OTHER)
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(OTHER)
                        .payingWith(OTHER)
                        .via("revoke-kyc-txn"),
                validateChargedSimpleFees("Simple Fees", "revoke-kyc-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate reject simple fees")
    final Stream<DynamicTest> validateReject() {
        // TOKEN_REJECT_BASE_FEE_USD (0.0009) ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                grantTokenKyc(FUNGIBLE_TOKEN, OTHER).fee(ONE_HUNDRED_HBARS).payingWith(OTHER),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(ADMIN, OTHER))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(ADMIN),
                tokenReject(rejectingToken(FUNGIBLE_TOKEN))
                        .fee(ONE_HUNDRED_HBARS)
                        .signedBy(OTHER)
                        .payingWith(OTHER)
                        .via("token-reject-txn"),
                validateChargedSimpleFees("Simple Fees", "token-reject-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate token account wipe simple fees")
    final Stream<DynamicTest> validateTokenAccountWipe() {
        // TOKEN_WIPE_BASE_FEE_USD (0.0009) ≈ 0.001
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("token-wipe-txn"),
                validateChargedSimpleFees("Simple Fees", "token-wipe-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate token fee schedule update simple fees")
    final Stream<DynamicTest> validateTokenFeeScheduleUpdate() {
        final var htsAmount = 2_345L;
        final var feeDenom = "denom";
        final var htsCollector = "denomFee";

        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("fee-schedule-update-txn"),
                validateChargedSimpleFees("Simple Fees", "fee-schedule-update-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate token update nfts simple fees")
    final Stream<DynamicTest> validateTokenUpdateNFTs() {
        final String NFT_TEST_METADATA = " test metadata";
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("token-update-nfts-txn"),
                validateChargedSimpleFees("Simple Fees", "token-update-nfts-txn", 0.001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate TokenGetInfoQuery simple fees")
    final Stream<DynamicTest> validateTokenGetInfo() {
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .payingWith(PAYER),
                validateChargedSimpleFees("Simple Fees", "get-token-info-query", 0.0001, 1));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("validate TokenGetNftInfoQuery simple fees")
    final Stream<DynamicTest> validateTokenGetNftInfo() {
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
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
                        .via("get-token-nft-info-query"),
                validateChargedSimpleFees("Simple Fees", "get-token-nft-info-query", 0.0001, 1));
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

    @HapiTest
    final Stream<DynamicTest> updateOneNftTokenWithoutCustomFees() {
        return updateBulkNftTokensAndValidateFees(List.of(1L));
    }

    @HapiTest
    final Stream<DynamicTest> updateFiveBulkNftTokensWithoutCustomFees() {
        return updateBulkNftTokensAndValidateFees(List.of(1L, 2L, 3L, 4L, 5L));
    }

    @HapiTest
    final Stream<DynamicTest> updateTenBulkNftTokensWithoutCustomFees() {
        return updateBulkNftTokensAndValidateFees(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
    }

    private Stream<DynamicTest> updateBulkNftTokensAndValidateFees(final List<Long> updateAmounts) {
        final var supplyKey = "supplyKey";
        return hapiTest(
                newKeyNamed(supplyKey),
                cryptoCreate("owner").balance(ONE_HUNDRED_HBARS).key(supplyKey),
                tokenCreate(NFT_TOKEN)
                        .treasury("owner")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(supplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0),
                mintToken(
                                NFT_TOKEN,
                                IntStream.range(0, updateAmounts.size())
                                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                        .toList())
                        .fee(updateAmounts.size() * ONE_HBAR)
                        .payingWith("owner")
                        .signedBy(supplyKey)
                        .blankMemo(),
                tokenUpdateNfts(NFT_TOKEN, "metadata", updateAmounts)
                        .fee(updateAmounts.size() * ONE_HBAR)
                        .payingWith("owner")
                        .signedBy(supplyKey)
                        .blankMemo()
                        .via("updateTxn"),
                validateChargedUsdWithin("updateTxn", TOKEN_UPDATE_NFT_FEE * updateAmounts.size(), 0.1));
    }
}
