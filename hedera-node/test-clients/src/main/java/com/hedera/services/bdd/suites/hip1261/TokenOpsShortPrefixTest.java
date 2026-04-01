// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.UNIQUE_PREFIXES;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferHbarFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAssociateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDissociateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFeeScheduleUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenGrantKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenPauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenRevokeKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnfreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnpauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenWipeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TYPES;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests that all multi-key token operations succeed with short (unique) sig-map prefixes
 * and charge fees within tolerance of the expected simple-fees amount.
 *
 * <p>A 5% tolerance is used because UNIQUE_PREFIXES generates variable-length prefix bytes
 * depending on actual key material, making the exact serialized transaction size
 * non-deterministic at authoring time.
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenOpsShortPrefixTest {
    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String TOKEN = "fungibleToken";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String WIPE_KEY = "wipeKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String KYC_KEY = "kycKey";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String COLLECTOR = "collector";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @HapiTest
    @DisplayName("TokenFreeze succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenFreezeWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(FREEZE_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(false)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                tokenFreeze(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, FREEZE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("freezeTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "freezeTxn",
                        txnSize ->
                                expectedTokenFreezeFullFeeUsd(Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenUnfreeze succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenUnfreezeWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(FREEZE_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                tokenUnfreeze(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, FREEZE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("unfreezeTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "unfreezeTxn",
                        txnSize -> expectedTokenUnfreezeFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenWipe succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenWipeWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(WIPE_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .wipeKey(WIPE_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT)).payingWith(TREASURY),
                wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                        .payingWith(PAYER)
                        .signedBy(PAYER, WIPE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("wipeTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "wipeTxn",
                        txnSize ->
                                expectedTokenWipeFullFeeUsd(Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenMint succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenMintWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                mintToken(TOKEN, 100L)
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("mintTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "mintTxn",
                        txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenBurn succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenBurnWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                burnToken(TOKEN, 100L)
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("burnTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "burnTxn",
                        txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenDelete succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenDeleteWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .adminKey(ADMIN_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenDelete(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("deleteTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "deleteTxn",
                        txnSize ->
                                expectedTokenDeleteFullFeeUsd(Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenKycGrant succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenKycGrantWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(KYC_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .kycKey(KYC_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                grantTokenKyc(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, KYC_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("grantKycTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "grantKycTxn",
                        txnSize -> expectedTokenGrantKycFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenKycRevoke succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenKycRevokeWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(KYC_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .kycKey(KYC_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                grantTokenKyc(TOKEN, ACCOUNT).payingWith(PAYER).signedBy(PAYER, KYC_KEY),
                revokeTokenKyc(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, KYC_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("revokeKycTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "revokeKycTxn",
                        txnSize -> expectedTokenRevokeKycFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenPause succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenPauseWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(PAUSE_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .pauseKey(PAUSE_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenPause(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, PAUSE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("pauseTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "pauseTxn",
                        txnSize ->
                                expectedTokenPauseFullFeeUsd(Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenUnpause succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenUnpauseWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(PAUSE_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .pauseKey(PAUSE_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY),
                tokenUnpause(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, PAUSE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("unpauseTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "unpauseTxn",
                        txnSize -> expectedTokenUnpauseFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenFeeScheduleUpdate succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenFeeScheduleUpdateWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(COLLECTOR).balance(0L),
                newKeyNamed(FEE_SCHEDULE_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenFeeScheduleUpdate(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, FEE_SCHEDULE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .withCustom(fixedHbarFee(1L, COLLECTOR))
                        .via("feeScheduleUpdateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "feeScheduleUpdateTxn",
                        txnSize -> expectedTokenFeeScheduleUpdateFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenAssociate succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenAssociateWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                tokenCreate(TOKEN).tokenType(FUNGIBLE_COMMON).treasury(TREASURY).payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ACCOUNT)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("associateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "associateTxn",
                        txnSize -> expectedTokenAssociateFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenDissociate succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenDissociateWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                tokenCreate(TOKEN).tokenType(FUNGIBLE_COMMON).treasury(TREASURY).payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(PAYER).signedBy(PAYER, ACCOUNT),
                tokenDissociate(ACCOUNT, TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ACCOUNT)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("dissociateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "dissociateTxn",
                        txnSize -> expectedTokenDissociateFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenCreate NFT succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenCreateNftWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate("nftToken")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .adminKey(ADMIN_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY, TREASURY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("nftCreateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "nftCreateTxn",
                        txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                SIGNATURES, 3L,
                                KEYS, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenMint NFT succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenMintNftWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate("nftToken")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                mintToken("nftToken", List.of(ByteString.copyFromUtf8("nft-metadata-1")))
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("mintNftTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "mintNftTxn",
                        txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                SIGNATURES, 2L,
                                KEYS, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenBurn NFT succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenBurnNftWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate("nftToken")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                mintToken("nftToken", List.of(ByteString.copyFromUtf8("nft-metadata-1")))
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY),
                burnToken("nftToken", List.of(1L))
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("burnNftTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "burnNftTxn",
                        txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    // -------- Non-token operations with UNIQUE_PREFIXES --------

    @HapiTest
    @DisplayName("CryptoTransfer HBAR succeeds with short (unique) prefixes")
    final Stream<DynamicTest> cryptoTransferHbarWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ACCOUNT).balance(0L),
                cryptoTransfer(tinyBarsFromTo(PAYER, ACCOUNT, 1L))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("hbarXferTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "hbarXferTxn",
                        txnSize -> expectedCryptoTransferHbarFullFeeUsd(Map.of(
                                SIGNATURES, 1L,
                                ACCOUNTS, 2L,
                                PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("CryptoTransfer fungible token succeeds with short (unique) prefixes")
    final Stream<DynamicTest> cryptoTransferFungibleTokenWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                        .payingWith(PAYER)
                        .signedBy(PAYER, TREASURY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("ftXferTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "ftXferTxn",
                        txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                SIGNATURES, 2L,
                                ACCOUNTS, 2L,
                                TOKEN_TYPES, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("CryptoTransfer NFT succeeds with short (unique) prefixes")
    final Stream<DynamicTest> cryptoTransferNftWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate("nftToken")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                mintToken("nftToken", List.of(ByteString.copyFromUtf8("nft-metadata-1")))
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY),
                tokenAssociate(ACCOUNT, "nftToken").payingWith(ACCOUNT),
                cryptoTransfer(movingUnique("nftToken", 1L).between(TREASURY, ACCOUNT))
                        .payingWith(PAYER)
                        .signedBy(PAYER, TREASURY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("nftXferTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "nftXferTxn",
                        txnSize -> expectedCryptoTransferNFTFullFeeUsd(Map.of(
                                SIGNATURES, 2L,
                                ACCOUNTS, 2L,
                                TOKEN_TYPES, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("CryptoUpdate succeeds with short (unique) prefixes")
    final Stream<DynamicTest> cryptoUpdateWithShortPrefixes() {
        final var newKey = "newKey";
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(newKey),
                cryptoUpdate(PAYER)
                        .key(newKey)
                        .payingWith(PAYER)
                        .signedBy(PAYER, newKey)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("cryptoUpdateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "cryptoUpdateTxn",
                        txnSize -> expectedCryptoUpdateFullFeeUsd(
                                Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenCreate fungible succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenCreateFungibleWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .adminKey(ADMIN_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY, TREASURY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("tokenCreateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "tokenCreateTxn",
                        txnSize -> expectedTokenCreateFullFeeUsd(Map.of(
                                SIGNATURES, 3L,
                                KEYS, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        5.0));
    }

    @HapiTest
    @DisplayName("TokenUpdate succeeds with short (unique) prefixes")
    final Stream<DynamicTest> tokenUpdateWithShortPrefixes() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                tokenCreate(TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .adminKey(ADMIN_KEY)
                        .treasury(TREASURY)
                        .payingWith(PAYER),
                tokenUpdate(TOKEN)
                        .memo("Updated memo")
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .via("tokenUpdateTxn"),
                validateChargedUsdWithinWithTxnSize(
                        "tokenUpdateTxn",
                        txnSize -> expectedTokenUpdateFullFeeUsd(Map.of(
                                SIGNATURES, 2L,
                                KEYS, 0L,
                                PROCESSING_BYTES, (long) txnSize)),
                        10.0));
    }
}
