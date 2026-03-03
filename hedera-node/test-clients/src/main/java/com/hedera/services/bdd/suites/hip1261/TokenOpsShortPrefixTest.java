// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.UNIQUE_PREFIXES;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFeeScheduleUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenFreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenGrantKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenPauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenRevokeKycFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnfreezeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnpauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenWipeFungibleFullFeeUsd;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
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
@Tag(MATS)
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                tokenFreeze(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, FREEZE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("freezeTxn"),
                validateChargedUsdWithin("freezeTxn", expectedTokenFreezeFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                tokenUnfreeze(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, FREEZE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("unfreezeTxn"),
                validateChargedUsdWithin("unfreezeTxn", expectedTokenUnfreezeFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                        .payingWith(TREASURY)
                        .fee(ONE_HUNDRED_HBARS),
                wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                        .payingWith(PAYER)
                        .signedBy(PAYER, WIPE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("wipeTxn"),
                validateChargedUsdWithin("wipeTxn", expectedTokenWipeFungibleFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                mintToken(TOKEN, 100L)
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("mintTxn"),
                validateChargedUsdWithin("mintTxn", expectedTokenMintFungibleFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                burnToken(TOKEN, 100L)
                        .payingWith(PAYER)
                        .signedBy(PAYER, SUPPLY_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("burnTxn"),
                validateChargedUsdWithin("burnTxn", expectedTokenBurnFungibleFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenDelete(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("deleteTxn"),
                validateChargedUsdWithin("deleteTxn", expectedTokenDeleteFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                grantTokenKyc(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, KYC_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("grantKycTxn"),
                validateChargedUsdWithin("grantKycTxn", expectedTokenGrantKycFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                grantTokenKyc(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, KYC_KEY)
                        .fee(ONE_HUNDRED_HBARS),
                revokeTokenKyc(TOKEN, ACCOUNT)
                        .payingWith(PAYER)
                        .signedBy(PAYER, KYC_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("revokeKycTxn"),
                validateChargedUsdWithin("revokeKycTxn", expectedTokenRevokeKycFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenPause(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, PAUSE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("pauseTxn"),
                validateChargedUsdWithin("pauseTxn", expectedTokenPauseFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenPause(TOKEN).payingWith(PAYER).signedBy(PAYER, PAUSE_KEY).fee(ONE_HUNDRED_HBARS),
                tokenUnpause(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, PAUSE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("unpauseTxn"),
                validateChargedUsdWithin("unpauseTxn", expectedTokenUnpauseFullFeeUsd(2L), 5.0));
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
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                tokenFeeScheduleUpdate(TOKEN)
                        .payingWith(PAYER)
                        .signedBy(PAYER, FEE_SCHEDULE_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES))
                        .withCustom(fixedHbarFee(1L, COLLECTOR))
                        .fee(ONE_HUNDRED_HBARS)
                        .via("feeScheduleUpdateTxn"),
                validateChargedUsdWithin(
                        "feeScheduleUpdateTxn", expectedTokenFeeScheduleUpdateFullFeeUsd(Map.of(SIGNATURES, 2L)), 5.0));
    }
}
