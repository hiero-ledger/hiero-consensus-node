// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AutoAssocAsserts.accountTokenPairsInAnyOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.salted;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.TokenFreezeStatus;
import com.hederahashgraph.api.proto.java.TokenKycStatus;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Validates the {@code TokenCreate} and {@code TokenUpdate} transactions, specifically its:
 * <ul>
 *     <li>Metadata and MetadataKey values and behaviours.</li>
 * </ul>
 */
@Tag(TOKEN)
@Tag(MATS)
public class TokenMetadataSpecs {
    private static final String PRIMARY = "primary";
    private static final String NON_FUNGIBLE_UNIQUE_FINITE = "non-fungible-unique-finite";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String CREATE_TXN = "createTxn";
    private static final String PAYER = "payer";
    private static final String METADATA_KEY = "metadataKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String KYC_KEY = "kycKey";
    private static final String TOKEN_TREASURY = "treasury";

    @HapiTest
    final Stream<DynamicTest> rejectsMetadataTooLong() {
        String metadataStringTooLong = TxnUtils.nAscii(101);
        return defaultHapiSpec("validatesMetadataLength")
                .given()
                .when()
                .then(tokenCreate(PRIMARY).metaData(metadataStringTooLong).hasPrecheck(METADATA_TOO_LONG));
    }

    @HapiTest
    final Stream<DynamicTest> creationDoesNotHaveRequiredSigs() {
        return defaultHapiSpec("CreationRequiresAppropriateSigs")
                .given(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY))
                .when()
                .then(
                        tokenCreate("shouldntWork")
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER)
                                .adminKey(ADMIN_KEY)
                                .signedBy(PAYER)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        /* treasury must sign */
                        tokenCreate("shouldntWorkEither")
                                .treasury(TOKEN_TREASURY)
                                .payingWith(PAYER)
                                .adminKey(ADMIN_KEY)
                                .signedBy(PAYER, ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> creationRequiresAppropriateSigsHappyPath() {
        return hapiTest(
                cryptoCreate(PAYER),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                newKeyNamed(ADMIN_KEY),
                tokenCreate("shouldWork")
                        .treasury(TOKEN_TREASURY)
                        .payingWith(PAYER)
                        .adminKey(ADMIN_KEY)
                        .signedBy(TOKEN_TREASURY, PAYER, ADMIN_KEY)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> fungibleCreationHappyPath() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .adminKey(ADMIN_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .metadataKey(METADATA_KEY)
                        .kycKey(KYC_KEY)
                        .freezeKey(FREEZE_KEY)
                        .metaData(metadata)
                        .via(CREATE_TXN),
                withOpContext((spec, opLog) -> {
                    var createTxn = getTxnRecord(CREATE_TXN);
                    allRunFor(spec, createTxn);
                    var timestamp = createTxn
                            .getResponseRecord()
                            .getConsensusTimestamp()
                            .getSeconds();
                    spec.registry().saveExpiry(PRIMARY, timestamp + THREE_MONTHS_IN_SECONDS);
                }),
                getTokenInfo(PRIMARY)
                        .logged()
                        .hasRegisteredId(PRIMARY)
                        .hasTokenType(TokenType.FUNGIBLE_COMMON)
                        .hasSupplyType(TokenSupplyType.FINITE)
                        .hasEntityMemo(memo)
                        .hasName(saltedName)
                        .hasTreasury(TOKEN_TREASURY)
                        .hasAutoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .hasValidExpiry()
                        .hasDecimals(1)
                        .hasAdminKey(PRIMARY)
                        .hasSupplyKey(PRIMARY)
                        .hasMetadataKey(PRIMARY)
                        .hasMetadata(metadata)
                        .hasMaxSupply(1000)
                        .hasTotalSupply(500)
                        .hasAutoRenewAccount(AUTO_RENEW_ACCOUNT),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(PRIMARY)
                                .balance(500)
                                .kyc(TokenKycStatus.Granted)
                                .freeze(TokenFreezeStatus.Unfrozen)));
    }

    @HapiTest
    final Stream<DynamicTest> nonFungibleCreationHappyPath() {
        String metadata = "metadata";
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                newKeyNamed(KYC_KEY),
                tokenCreate(NON_FUNGIBLE_UNIQUE_FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .initialSupply(0)
                        .maxSupply(100)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(GENESIS)
                        .metadataKey(METADATA_KEY)
                        .kycKey(KYC_KEY)
                        .metaData(metadata)
                        .via(CREATE_TXN),
                getTxnRecord(CREATE_TXN)
                        .logged()
                        .hasPriority(recordWith()
                                .autoAssociated(accountTokenPairsInAnyOrder(
                                        List.of(Pair.of(TOKEN_TREASURY, NON_FUNGIBLE_UNIQUE_FINITE))))),
                withOpContext((spec, opLog) -> {
                    var createTxn = getTxnRecord(CREATE_TXN);
                    allRunFor(spec, createTxn);
                    var timestamp = createTxn
                            .getResponseRecord()
                            .getConsensusTimestamp()
                            .getSeconds();
                    spec.registry().saveExpiry(NON_FUNGIBLE_UNIQUE_FINITE, timestamp + THREE_MONTHS_IN_SECONDS);
                }),
                getTokenInfo(NON_FUNGIBLE_UNIQUE_FINITE)
                        .logged()
                        .hasRegisteredId(NON_FUNGIBLE_UNIQUE_FINITE)
                        .hasTokenType(NON_FUNGIBLE_UNIQUE)
                        .hasSupplyType(TokenSupplyType.FINITE)
                        .hasTotalSupply(0)
                        .hasMaxSupply(100),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(NON_FUNGIBLE_UNIQUE_FINITE)
                                .balance(0)
                                .kyc(TokenKycStatus.Granted)));
    }

    @HapiTest
    final Stream<DynamicTest> updatingMetadataWorksWithMetadataKey() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(100L),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .metadataKey(METADATA_KEY)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .metaData(metadata),
                getTokenInfo(PRIMARY).hasMetadata(metadata),
                tokenUpdate(PRIMARY).newMetadata("newMetadata").signedBy(DEFAULT_PAYER, METADATA_KEY),
                getTokenInfo(PRIMARY).hasMetadata("newMetadata"));
    }

    @HapiTest
    final Stream<DynamicTest> updatingMetadataWorksWithAdminKey() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(100L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .adminKey(ADMIN_KEY)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .metaData(metadata),
                getTokenInfo(PRIMARY).hasMetadata(metadata),
                tokenUpdate(PRIMARY).newMetadata("newMetadata").signedBy(DEFAULT_PAYER, ADMIN_KEY),
                getTokenInfo(PRIMARY).hasMetadata("newMetadata"));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateMetadataWithoutAdminOrMetadataKeySignature() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(100L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .adminKey(ADMIN_KEY)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .metaData(metadata),
                getTokenInfo(PRIMARY).logged(),
                tokenUpdate(PRIMARY)
                        .newMetadata("newMetadata")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateMetadataOnImmutableToken() {
        String memo = "JUMP";
        String metadata = "metadata";
        String saltedName = salted(PRIMARY);

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(100L),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                tokenCreate(PRIMARY)
                        .supplyType(TokenSupplyType.FINITE)
                        .entityMemo(memo)
                        .name(saltedName)
                        .treasury(TOKEN_TREASURY)
                        .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                        .maxSupply(1000)
                        .initialSupply(500)
                        .decimals(1)
                        .metaData(metadata),
                getTokenInfo(PRIMARY).logged(),
                tokenUpdate(PRIMARY)
                        .newMetadata("newMetadata")
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(TOKEN_IS_IMMUTABLE));
    }
}
