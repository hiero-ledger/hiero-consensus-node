// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKycWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.newAliasedAccount;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKycWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociateWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociateWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreezeWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreezeWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccountWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.CIVILIAN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_MAX_SUPPLY_REACHED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenManagementSpecs. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
public class AtomicTokenManagementSpecs {

    private static final String SUPPLE = "supple";
    private static final String SHOULD_NOT_APPEAR = "should-not-appear";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String WIPE_TXN = "wipeTxn";
    private static final String ONE_KYC = "oneKyc";
    private static final String RIGID = "rigid";
    public static final String INVALID_ACCOUNT = "999.999.999";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    private HapiSpecOperation[] aliasFormFailsForAllTokenOpsBase() {
        final var CIVILIAN = "civilian";
        final var PAUSE_KEY = "pauseKey";
        final var KYC_KEY = "kycKey";
        final var FREEZE_KEY = "freezeKey";
        final var WIPE_KEY = "wipeKey";
        final var PRIMARY = "primary";
        final var partyAlias = "partyAlias";
        final var counterAlias = "counterAlias";

        return new HapiSpecOperation[] {
            newKeyNamed(partyAlias).shape(ED25519),
            newKeyNamed(counterAlias).shape(ED25519),
            cryptoCreate(TOKEN_TREASURY),
            cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
            newKeyNamed(PAUSE_KEY),
            newKeyNamed(KYC_KEY),
            newKeyNamed(FREEZE_KEY),
            newKeyNamed(WIPE_KEY),
            newAliasedAccount(partyAlias),
            tokenCreate(PRIMARY)
                    .tokenType(FUNGIBLE_COMMON)
                    .supplyType(TokenSupplyType.FINITE)
                    .maxSupply(1000)
                    .initialSupply(500)
                    .decimals(1)
                    .treasury(TOKEN_TREASURY)
                    .pauseKey(PAUSE_KEY)
                    .kycKey(KYC_KEY)
                    .freezeKey(FREEZE_KEY)
                    .wipeKey(WIPE_KEY)
        };
    }

    @HapiTest
    final Stream<DynamicTest> aliasFormFailsForAllTokenOps() {
        final var PRIMARY = "primary";
        final var partyAlias = "partyAlias";
        return hapiTest(flattened(
                aliasFormFailsForAllTokenOpsBase(),
                atomicBatch(tokenAssociateWithAlias(partyAlias, PRIMARY)
                                .signedBy(partyAlias, DEFAULT_PAYER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ACCOUNT_ID),
                atomicBatch(tokenDissociateWithAlias(partyAlias, PRIMARY)
                                .signedBy(partyAlias, DEFAULT_PAYER)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenAssociateWithAlias(partyAlias, PRIMARY)
                                .signedBy(partyAlias, DEFAULT_PAYER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ACCOUNT_ID),
                atomicBatch(grantTokenKycWithAlias(PRIMARY, partyAlias)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(revokeTokenKycWithAlias(PRIMARY, partyAlias)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenFreezeWithAlias(PRIMARY, partyAlias)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(tokenUnfreezeWithAlias(PRIMARY, partyAlias)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(grantTokenKycWithAlias(PRIMARY, partyAlias)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> getNftInfoIdVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate("nft")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(mintToken("nft", List.of(copyFromUtf8("Please mind the vase.")))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                sendModified(withSuccessivelyVariedQueryIds(), () -> getTokenNftInfo("nft", 1L)));
    }

    @HapiTest
    final Stream<DynamicTest> frozenTreasuryCannotBeMintedOrBurned() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed("freezeKey"),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(SUPPLE)
                        .freezeKey("freezeKey")
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(1)
                        .treasury(TOKEN_TREASURY),
                tokenFreeze(SUPPLE, TOKEN_TREASURY),
                atomicBatch(mintToken(SUPPLE, 1)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(burnToken(SUPPLE, 1)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(SUPPLE).hasTotalSupply(1),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(SUPPLE).balance(1).freeze(Frozen)));
    }

    @HapiTest
    final Stream<DynamicTest> revokedKYCTreasuryCannotBeMintedOrBurned() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed("kycKey"),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(SUPPLE)
                        .kycKey("kycKey")
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(1)
                        .treasury(TOKEN_TREASURY),
                revokeTokenKyc(SUPPLE, TOKEN_TREASURY),
                atomicBatch(mintToken(SUPPLE, 1)
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(burnToken(SUPPLE, 1)
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(SUPPLE).hasTotalSupply(1),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(SUPPLE).balance(1).kyc(Revoked)));
    }

    @HapiTest
    final Stream<DynamicTest> burnTokenFailsDueToInsufficientTreasuryBalance() {
        final String BURN_TOKEN = "burn";
        final int TOTAL_SUPPLY = 100;
        final int TRANSFER_AMOUNT = 50;
        final int BURN_AMOUNT = 60;

        return hapiTest(
                newKeyNamed("burnKey"),
                cryptoCreate("misc").balance(0L),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(BURN_TOKEN)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .supplyKey("burnKey"),
                tokenAssociate("misc", BURN_TOKEN),
                cryptoTransfer(moving(TRANSFER_AMOUNT, BURN_TOKEN).between(TOKEN_TREASURY, "misc")),
                getAccountBalance("misc").hasTokenBalance(BURN_TOKEN, TRANSFER_AMOUNT),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(BURN_TOKEN, TRANSFER_AMOUNT),
                getAccountInfo("misc").logged(),
                atomicBatch(burnToken(BURN_TOKEN, BURN_AMOUNT)
                                .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)
                                .via(WIPE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(BURN_TOKEN),
                getAccountInfo("misc"),
                getTokenInfo(BURN_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(BURN_TOKEN, TRANSFER_AMOUNT),
                getTxnRecord(WIPE_TXN).logged());
    }

    private HapiSpecOperation[] wipeAccountFailureCasesWorkBase() {
        var unwipeableToken = "without";
        var wipeableToken = "with";
        var wipeableUniqueToken = "uniqueWith";
        var anotherWipeableToken = "anotherWith";
        var multiKey = "wipeAndSupplyKey";
        var someMeta = ByteString.copyFromUtf8("HEY");

        return new HapiSpecOperation[] {
            newKeyNamed(multiKey),
            newKeyNamed("alias").type(KeyFactory.KeyType.SIMPLE),
            cryptoCreate("misc").balance(0L),
            cryptoCreate(TOKEN_TREASURY).balance(0L),
            tokenCreate(unwipeableToken).treasury(TOKEN_TREASURY),
            tokenCreate(wipeableToken).treasury(TOKEN_TREASURY).wipeKey(multiKey),
            tokenCreate(wipeableUniqueToken)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .supplyKey(multiKey)
                    .initialSupply(0L)
                    .treasury(TOKEN_TREASURY)
                    .wipeKey(multiKey),
            mintToken(wipeableUniqueToken, List.of(someMeta)),
            tokenCreate(anotherWipeableToken)
                    .treasury(TOKEN_TREASURY)
                    .initialSupply(1_000)
                    .wipeKey(multiKey),
            tokenAssociate("misc", anotherWipeableToken),
            cryptoTransfer(moving(500, anotherWipeableToken).between(TOKEN_TREASURY, "misc"))
        };
    }

    @HapiTest
    final Stream<DynamicTest> wipeAccountFailureCasesWork() {
        var unwipeableToken = "without";
        var wipeableToken = "with";
        var wipeableUniqueToken = "uniqueWith";
        var anotherWipeableToken = "anotherWith";

        return hapiTest(flattened(
                wipeAccountFailureCasesWorkBase(),
                atomicBatch(wipeTokenAccount(wipeableUniqueToken, TOKEN_TREASURY, List.of(1L))
                                .hasKnownStatus(CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(unwipeableToken, TOKEN_TREASURY, 1)
                                .signedBy(GENESIS)
                                .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(wipeableToken, "misc", 1)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(wipeableToken, TOKEN_TREASURY, 1)
                                .signedBy(GENESIS)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(wipeableToken, TOKEN_TREASURY, 1)
                                .hasKnownStatus(CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(anotherWipeableToken, "misc", 501)
                                .hasKnownStatus(INVALID_WIPING_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(wipeTokenAccount(anotherWipeableToken, "misc", -1)
                                .hasPrecheck(INVALID_WIPING_AMOUNT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_WIPING_AMOUNT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        atomicBatch(wipeTokenAccountWithAlias(unwipeableToken, "alias", 1)
                                        .signedBy(GENESIS)
                                        .hasKnownStatus(INVALID_ACCOUNT_ID)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED)))));
    }

    @HapiTest
    final Stream<DynamicTest> kycMgmtFailureCasesWork() {
        var withoutKycKey = "withoutKycKey";
        var withKycKey = "withKycKey";

        return hapiTest(
                newKeyNamed(ONE_KYC),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                tokenCreate(withoutKycKey).treasury(TOKEN_TREASURY),
                tokenCreate(withKycKey).kycKey(ONE_KYC).treasury(TOKEN_TREASURY),
                atomicBatch(grantTokenKyc(withoutKycKey, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(grantTokenKyc(withKycKey, INVALID_ACCOUNT)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(grantTokenKyc(withKycKey, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(grantTokenKyc(withoutKycKey, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(revokeTokenKyc(withKycKey, INVALID_ACCOUNT)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ACCOUNT_ID))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(revokeTokenKyc(withKycKey, TOKEN_TREASURY)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> updateIdVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate("autoRenewAccount"),
                tokenCreate("t").adminKey("adminKey"),
                atomicBatch(tokenUpdate("t")
                                .autoRenewPeriod(7776000L)
                                .autoRenewAccount("autoRenewAccount")
                                .signedBy(DEFAULT_PAYER, "adminKey", "autoRenewAccount")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> freezeMgmtSuccessCasesWork() {
        var withPlusDefaultFalse = "withPlusDefaultFalse";

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate("misc").balance(0L),
                newKeyNamed("oneFreeze"),
                newKeyNamed("twoFreeze"),
                tokenCreate(withPlusDefaultFalse)
                        .freezeDefault(false)
                        .freezeKey("twoFreeze")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate("misc", withPlusDefaultFalse),
                atomicBatch(
                                cryptoTransfer(moving(1, withPlusDefaultFalse).between(TOKEN_TREASURY, "misc"))
                                        .batchKey(BATCH_OPERATOR),
                                tokenFreeze(withPlusDefaultFalse, "misc").batchKey(BATCH_OPERATOR),
                                tokenUnfreeze(withPlusDefaultFalse, "misc").batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(1, withPlusDefaultFalse).between(TOKEN_TREASURY, "misc"))
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR));
    }

    @HapiTest
    final Stream<DynamicTest> kycMgmtSuccessCasesWork() {
        var withKycKey = "withKycKey";
        var withoutKycKey = "withoutKycKey";

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate("misc").balance(0L),
                newKeyNamed(ONE_KYC),
                newKeyNamed("twoKyc"),
                tokenCreate(withKycKey).kycKey(ONE_KYC).treasury(TOKEN_TREASURY),
                tokenCreate(withoutKycKey).treasury(TOKEN_TREASURY),
                tokenAssociate("misc", withKycKey, withoutKycKey),
                atomicBatch(cryptoTransfer(moving(1, withKycKey).between(TOKEN_TREASURY, "misc"))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(
                                grantTokenKyc(withKycKey, "misc").batchKey(BATCH_OPERATOR),
                                cryptoTransfer(moving(1, withKycKey).between(TOKEN_TREASURY, "misc"))
                                        .batchKey(BATCH_OPERATOR),
                                revokeTokenKyc(withKycKey, "misc").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(cryptoTransfer(moving(1, withKycKey).between(TOKEN_TREASURY, "misc"))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(cryptoTransfer(moving(1, withoutKycKey).between(TOKEN_TREASURY, "misc"))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo("misc").logged());
    }

    @HapiTest
    final Stream<DynamicTest> fungibleCommonMaxSupplyReachWork() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(0)
                        .maxSupply(500)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                atomicBatch(mintToken(FUNGIBLE_TOKEN, 3000)
                                .hasKnownStatus(TOKEN_MAX_SUPPLY_REACHED)
                                .via(SHOULD_NOT_APPEAR)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> supplyMgmtFailureCasesWork() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(RIGID),
                tokenCreate(SUPPLE).supplyKey(SUPPLY_KEY).decimals(16).initialSupply(1),
                atomicBatch(mintToken(RIGID, 1)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(burnToken(RIGID, 1)
                                .signedBy(GENESIS)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(mintToken(SUPPLE, Long.MAX_VALUE)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_TOKEN_MINT_AMOUNT))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(mintToken(SUPPLE, 0).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                atomicBatch(mintToken(SUPPLE, -1).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_MINT_AMOUNT),
                atomicBatch(burnToken(SUPPLE, 2).batchKey(BATCH_OPERATOR).hasKnownStatus(INVALID_TOKEN_BURN_AMOUNT))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(burnToken(SUPPLE, 0).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                atomicBatch(burnToken(SUPPLE, -1).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_TOKEN_BURN_AMOUNT));
    }
}
