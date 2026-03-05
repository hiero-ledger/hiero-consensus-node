// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreezeWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccountWithAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.A_TOKEN;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.TOKEN_A_CREATE;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.VALID_ALIAS;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.CIVILIAN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_MAX_SUPPLY_REACHED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Unfrozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Granted;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.FINITE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class TokenManagementSpecs {
    private static final String SUPPLE = "supple";
    private static final String SHOULD_NOT_APPEAR = "should-not-appear";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String MINT_TXN = "mintTxn";
    private static final String WIPE_TXN = "wipeTxn";
    private static final String ONE_KYC = "oneKyc";
    private static final String RIGID = "rigid";
    public static final String INVALID_ACCOUNT = "999.999.999";

    @HapiTest
    final Stream<DynamicTest> aliasFormFailsForAllTokenOps() {
        final var CIVILIAN = "civilian";
        final var PAUSE_KEY = "pauseKey";
        final var KYC_KEY = "kycKey";
        final var FREEZE_KEY = "freezeKey";
        final var WIPE_KEY = "wipeKey";
        final var PRIMARY = "primary";
        final var partyAlias = "partyAlias";
        final var counterAlias = "counterAlias";
        return defaultHapiSpec("aliasFormFailsForAllTokenOps")
                .given(
                        newKeyNamed(partyAlias).shape(ED25519),
                        newKeyNamed(counterAlias).shape(ED25519),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAUSE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(WIPE_KEY),
                        newAliasedAccount(partyAlias))
                .when(
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
                                .wipeKey(WIPE_KEY),

                        // associate and dissociate with alias
                        tokenAssociateWithAlias(partyAlias, PRIMARY)
                                .signedBy(partyAlias, DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID),
                        tokenDissociateWithAlias(partyAlias, PRIMARY)
                                .signedBy(partyAlias, DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID),
                        // associate again for next steps
                        tokenAssociateWithAlias(partyAlias, PRIMARY)
                                .signedBy(partyAlias, DEFAULT_PAYER)
                                .hasPrecheck(INVALID_ACCOUNT_ID),
                        // grant and revoke kyc
                        grantTokenKycWithAlias(PRIMARY, partyAlias).hasPrecheck(INVALID_ACCOUNT_ID),
                        // revoke kyc
                        revokeTokenKycWithAlias(PRIMARY, partyAlias).hasPrecheck(INVALID_ACCOUNT_ID),
                        // freeze, unfreeze
                        tokenFreezeWithAlias(PRIMARY, partyAlias).hasPrecheck(INVALID_ACCOUNT_ID),
                        tokenUnfreezeWithAlias(PRIMARY, partyAlias).hasPrecheck(INVALID_ACCOUNT_ID),

                        // wipe won't happen if the kyc key exists and kyc not granted
                        grantTokenKycWithAlias(PRIMARY, partyAlias)
                                .hasPrecheck(INVALID_ACCOUNT_ID)
                                .logged(),
                        tokenAssociate(partyAlias, PRIMARY),
                        grantTokenKyc(PRIMARY, partyAlias),
                        cryptoTransfer(moving(1, PRIMARY).between(TOKEN_TREASURY, partyAlias))
                                .signedBy(DEFAULT_PAYER, TOKEN_TREASURY),
                        // Only wipe works with alias apart from CryptoTransfer
                        wipeTokenAccountWithAlias(PRIMARY, partyAlias, 1))
                .then();
    }

    //    @HapiTest
    // This test should be enabled when aliases are supported in all transaction bodies
    final Stream<DynamicTest> aliasFormWorksForAllTokenOps() {
        final var CIVILIAN = "civilian";
        final var PAUSE_KEY = "pauseKey";
        final var KYC_KEY = "kycKey";
        final var FREEZE_KEY = "freezeKey";
        final var WIPE_KEY = "wipeKey";
        final var PRIMARY = "primary";
        final var partyAlias = "partyAlias";
        final var counterAlias = "counterAlias";
        return defaultHapiSpec("aliasFormWorksForAllTokenOps")
                .given(
                        newKeyNamed(partyAlias).shape(ED25519),
                        newKeyNamed(counterAlias).shape(ED25519),
                        cryptoCreate(TOKEN_TREASURY),
                        cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAUSE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(WIPE_KEY),
                        cryptoTransfer(tinyBarsFromToWithAlias(CIVILIAN, partyAlias, ONE_HBAR)),
                        cryptoTransfer(tinyBarsFromToWithAlias(CIVILIAN, counterAlias, ONE_HBAR)))
                .when(
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
                                .wipeKey(WIPE_KEY),

                        // associate and dissociate with alias
                        tokenAssociateWithAlias(partyAlias, PRIMARY).signedBy(partyAlias, DEFAULT_PAYER),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY).balance(0).kyc(Revoked))
                                .logged(),
                        tokenDissociateWithAlias(partyAlias, PRIMARY).signedBy(partyAlias, DEFAULT_PAYER),
                        getAliasedAccountInfo(partyAlias)
                                .hasNoTokenRelationship(PRIMARY)
                                .logged(),

                        // associate again for next steps
                        tokenAssociateWithAlias(partyAlias, PRIMARY).signedBy(partyAlias, DEFAULT_PAYER),

                        // grant and revoke kyc
                        grantTokenKycWithAlias(PRIMARY, partyAlias),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY).balance(0).kyc(Granted))
                                .logged(),
                        // transfer some tokens
                        cryptoTransfer(moving(10, PRIMARY).between(TOKEN_TREASURY, partyAlias))
                                .signedBy(DEFAULT_PAYER, TOKEN_TREASURY),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY).balance(10).kyc(Granted))
                                .logged(),

                        // revoke kyc
                        revokeTokenKycWithAlias(PRIMARY, partyAlias),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY).balance(10).kyc(Revoked))
                                .logged(),

                        // freeze, unfreeze
                        tokenFreezeWithAlias(PRIMARY, partyAlias),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY)
                                        .balance(10)
                                        .kyc(Revoked)
                                        .freeze(Frozen))
                                .logged(),
                        tokenUnfreezeWithAlias(PRIMARY, partyAlias),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY)
                                        .balance(10)
                                        .kyc(Revoked)
                                        .freeze(Unfrozen))
                                .logged(),

                        // wipe won't happen if the kyc key exists and kyc not granted
                        grantTokenKycWithAlias(PRIMARY, partyAlias),
                        wipeTokenAccountWithAlias(PRIMARY, partyAlias, 1),
                        getAliasedAccountInfo(partyAlias)
                                .hasToken(relationshipWith(PRIMARY)
                                        .balance(9)
                                        .kyc(Granted)
                                        .freeze(Unfrozen))
                                .logged())
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> getNftInfoIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getNftInfoIdVariantsTreatedAsExpected")
                .given(newKeyNamed("supplyKey"), cryptoCreate(TOKEN_TREASURY).balance(0L))
                .when(
                        tokenCreate("nft")
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyKey("supplyKey")
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY),
                        mintToken("nft", List.of(copyFromUtf8("Please mind the vase."))))
                .then(sendModified(withSuccessivelyVariedQueryIds(), () -> getTokenNftInfo("nft", 1L)));
    }

    // FULLY_NONDETERMINISTIC because in mono-service zero amount token transfers will create a tokenTransferLists
    // with a just tokenNum, in mono-service the tokenTransferLists will be empty
    @HapiTest
    final Stream<DynamicTest> zeroUnitTokenOperationsWorkAsExpected() {
        final var civilian = "civilian";
        final var adminKey = "adminKey";
        final var fungible = "fungible";
        final var nft = "non-fungible";
        return hapiTest(
                newKeyNamed(adminKey),
                cryptoCreate(TOKEN_TREASURY).balance(0L),
                cryptoCreate(civilian).balance(0L),
                tokenCreate(fungible)
                        .supplyKey(adminKey)
                        .adminKey(adminKey)
                        .wipeKey(adminKey)
                        .supplyType(TokenSupplyType.FINITE)
                        .maxSupply(100)
                        .initialSupply(10)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(adminKey)
                        .adminKey(adminKey)
                        .wipeKey(adminKey)
                        .maxSupply(10)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(civilian, fungible, nft),
                mintToken(nft, List.of(copyFromUtf8("Please mind the vase."))),
                cryptoTransfer(moving(2, fungible).between(TOKEN_TREASURY, civilian))
                        .logged(),
                cryptoTransfer(movingUnique(nft, 1L).between(TOKEN_TREASURY, civilian))
                        .logged(),
                getAccountInfo(civilian)
                        .hasToken(relationshipWith(fungible).balance(2))
                        .hasOwnedNfts(1)
                        .logged(),
                cryptoTransfer(moving(0, fungible).between(TOKEN_TREASURY, civilian))
                        .logged(),
                mintToken(fungible, 0),
                mintToken(nft, List.of()).hasKnownStatus(INVALID_TOKEN_MINT_METADATA),
                burnToken(fungible, 0),
                burnToken(nft, List.of()).hasKnownStatus(INVALID_TOKEN_BURN_METADATA),
                wipeTokenAccount(fungible, civilian, 0),
                wipeTokenAccount(nft, civilian, List.of()).hasKnownStatus(INVALID_WIPING_AMOUNT),
                getAccountInfo(TOKEN_TREASURY)
                        .hasToken(relationshipWith(fungible).balance(8))
                        .hasOwnedNfts(0)
                        .logged(),
                getAccountInfo(civilian)
                        .hasToken(relationshipWith(fungible).balance(2))
                        .hasOwnedNfts(1)
                        .logged());
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
                mintToken(SUPPLE, 1).hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                burnToken(SUPPLE, 1).hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
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
                mintToken(SUPPLE, 1).hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
                burnToken(SUPPLE, 1).hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
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
                burnToken(BURN_TOKEN, BURN_AMOUNT)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)
                        .via(WIPE_TXN),
                getTokenInfo(BURN_TOKEN),
                getAccountInfo("misc"),
                getTokenInfo(BURN_TOKEN).hasTotalSupply(TOTAL_SUPPLY),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(BURN_TOKEN, TRANSFER_AMOUNT),
                getTxnRecord(WIPE_TXN).logged());
    }

    @HapiTest
    final Stream<DynamicTest> wipeAccountSuccessCasesWork() {
        var wipeableToken = "with";

        return defaultHapiSpec("WipeAccountSuccessCasesWork")
                .given(
                        newKeyNamed("wipeKey"),
                        cryptoCreate("misc").balance(0L),
                        cryptoCreate(TOKEN_TREASURY).balance(0L))
                .when(
                        tokenCreate(wipeableToken)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(1_000)
                                .wipeKey("wipeKey"),
                        tokenAssociate("misc", wipeableToken),
                        cryptoTransfer(moving(500, wipeableToken).between(TOKEN_TREASURY, "misc")),
                        getAccountBalance("misc").hasTokenBalance(wipeableToken, 500),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(wipeableToken, 500),
                        getAccountInfo("misc").logged(),
                        wipeTokenAccount(wipeableToken, "misc", 500).via(WIPE_TXN),
                        getAccountInfo("misc").logged(),
                        wipeTokenAccount(wipeableToken, "misc", 0).via("wipeWithZeroAmount"),
                        getAccountInfo("misc").logged())
                .then(
                        getAccountBalance("misc").hasTokenBalance(wipeableToken, 0),
                        cryptoDelete("misc"),
                        getTokenInfo(wipeableToken).hasTotalSupply(500),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(wipeableToken, 500),
                        getTxnRecord(WIPE_TXN).logged());
    }

    @HapiTest
    final Stream<DynamicTest> wipeAccountWithAliasesWork() {
        final var initialTokenSupply = 1000;
        return defaultHapiSpec("wipeAccountWithAliasesWork")
                .given(
                        newKeyNamed(VALID_ALIAS),
                        newKeyNamed("wipeKey"),
                        cryptoCreate(TOKEN_TREASURY).balance(10 * ONE_HUNDRED_HBARS),
                        cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                        tokenCreate(A_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(FINITE)
                                .initialSupply(initialTokenSupply)
                                .maxSupply(10L * initialTokenSupply)
                                .wipeKey("wipeKey")
                                .treasury(TOKEN_TREASURY)
                                .via(TOKEN_A_CREATE),
                        getTxnRecord(TOKEN_A_CREATE).hasNewTokenAssociation(A_TOKEN, TOKEN_TREASURY),
                        tokenAssociate(CIVILIAN, A_TOKEN),
                        cryptoTransfer(moving(10, A_TOKEN).between(TOKEN_TREASURY, CIVILIAN)),
                        getAccountInfo(CIVILIAN)
                                .hasToken(relationshipWith(A_TOKEN).balance(10)))
                .when(
                        cryptoTransfer(
                                        movingHbar(10L).between(CIVILIAN, VALID_ALIAS),
                                        moving(5, A_TOKEN).between(CIVILIAN, VALID_ALIAS))
                                .signedBy(DEFAULT_PAYER, CIVILIAN)
                                .via(TRANSFER_TXN),
                        getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                        getAliasedAccountInfo(VALID_ALIAS)
                                .has(accountWith().balance(10L))
                                .hasToken(relationshipWith(A_TOKEN).balance(5L))
                                .logged())
                .then(
                        wipeTokenAccountWithAlias(A_TOKEN, VALID_ALIAS, 4).via(WIPE_TXN),
                        getAliasedAccountInfo(VALID_ALIAS)
                                .has(accountWith().balance(10L))
                                .hasToken(relationshipWith(A_TOKEN).balance(1L))
                                .logged(),
                        wipeTokenAccountWithAlias(A_TOKEN, VALID_ALIAS, 0),
                        getAliasedAccountInfo(VALID_ALIAS)
                                .has(accountWith().balance(10L))
                                .hasToken(relationshipWith(A_TOKEN).balance(1L))
                                .logged());
    }

    @HapiTest
    final Stream<DynamicTest> wipeAccountFailureCasesWork() {
        var unwipeableToken = "without";
        var wipeableToken = "with";
        var wipeableUniqueToken = "uniqueWith";
        var anotherWipeableToken = "anotherWith";
        var multiKey = "wipeAndSupplyKey";
        var someMeta = ByteString.copyFromUtf8("HEY");

        return hapiTest(
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
                cryptoTransfer(moving(500, anotherWipeableToken).between(TOKEN_TREASURY, "misc")),
                wipeTokenAccount(wipeableUniqueToken, TOKEN_TREASURY, List.of(1L))
                        .hasKnownStatus(CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT),
                wipeTokenAccount(unwipeableToken, TOKEN_TREASURY, 1)
                        .signedBy(GENESIS)
                        .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY),
                wipeTokenAccount(wipeableToken, "misc", 1).hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                wipeTokenAccount(wipeableToken, TOKEN_TREASURY, 1)
                        .signedBy(GENESIS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                wipeTokenAccount(wipeableUniqueToken, TOKEN_TREASURY, 1)
                        .signedBy(GENESIS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                wipeTokenAccount(wipeableToken, TOKEN_TREASURY, 1).hasKnownStatus(CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT),
                wipeTokenAccount(anotherWipeableToken, "misc", 501).hasKnownStatus(INVALID_WIPING_AMOUNT),
                wipeTokenAccount(anotherWipeableToken, "misc", -1).hasPrecheck(INVALID_WIPING_AMOUNT),
                withOpContext((spec, opLog) -> {
                    final var key = spec.registry().getKey("alias");
                    final var alias = key.hasECDSASecp256K1() ? key.getECDSASecp256K1() : key.getEd25519();
                    allRunFor(
                            spec,
                            wipeTokenAccountWithAlias(unwipeableToken, "alias", 1)
                                    .signedBy(GENESIS)
                                    .hasKnownStatus(INVALID_ACCOUNT_ID));
                }));
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
                grantTokenKyc(withoutKycKey, TOKEN_TREASURY).signedBy(GENESIS).hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                grantTokenKyc(withKycKey, INVALID_ACCOUNT).hasKnownStatus(INVALID_ACCOUNT_ID),
                grantTokenKyc(withKycKey, TOKEN_TREASURY).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                grantTokenKyc(withoutKycKey, TOKEN_TREASURY).signedBy(GENESIS).hasKnownStatus(TOKEN_HAS_NO_KYC_KEY),
                revokeTokenKyc(withKycKey, INVALID_ACCOUNT).hasKnownStatus(INVALID_ACCOUNT_ID),
                revokeTokenKyc(withKycKey, TOKEN_TREASURY).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                getTokenInfo(withoutKycKey).hasRegisteredId(withoutKycKey).logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("updateIdVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate("autoRenewAccount"),
                        tokenCreate("t").adminKey("adminKey"))
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> tokenUpdate("t")
                        .autoRenewPeriod(7776000L)
                        .autoRenewAccount("autoRenewAccount")
                        .signedBy(DEFAULT_PAYER, "adminKey", "autoRenewAccount")));
    }

    @HapiTest
    final Stream<DynamicTest> wipeIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("wipeIdVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("wipeKey"),
                        cryptoCreate("holder").maxAutomaticTokenAssociations(2),
                        tokenCreate("t").initialSupply(1000).wipeKey("wipeKey"))
                .when(cryptoTransfer(moving(100, "t").between(DEFAULT_PAYER, "holder")))
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> wipeTokenAccount("t", "holder", 1)));
    }

    @HapiTest
    final Stream<DynamicTest> grantRevokeIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("grantRevokeIdVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("kycKey"),
                        cryptoCreate("somebody"),
                        cryptoCreate("feeCollector"),
                        tokenCreate("t").kycKey("kycKey"),
                        tokenAssociate("somebody", "t"))
                .when()
                .then(
                        submitModified(withSuccessivelyVariedBodyIds(), () -> grantTokenKyc("t", "somebody")),
                        submitModified(withSuccessivelyVariedBodyIds(), () -> revokeTokenKyc("t", "somebody")));
    }

    @HapiTest
    final Stream<DynamicTest> pauseUnpauseIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("pauseUnpauseIdVariantsTreatedAsExpected")
                .given(newKeyNamed("pauseKey"), tokenCreate("t").pauseKey("pauseKey"))
                .when()
                .then(
                        submitModified(withSuccessivelyVariedBodyIds(), () -> tokenPause("t")),
                        submitModified(withSuccessivelyVariedBodyIds(), () -> tokenUnpause("t")));
    }

    @HapiTest
    final Stream<DynamicTest> freezeUnfreezeIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("freezeUnfreezeIdVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("freezeKey"),
                        cryptoCreate("somebody"),
                        tokenCreate("t").freezeKey("freezeKey"),
                        tokenAssociate("somebody", "t"))
                .when()
                .then(
                        submitModified(withSuccessivelyVariedBodyIds(), () -> tokenFreeze("t", "somebody")),
                        submitModified(withSuccessivelyVariedBodyIds(), () -> tokenUnfreeze("t", "somebody")));
    }

    @HapiTest
    final Stream<DynamicTest> mintBurnIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("mintBurnIdVariantsTreatedAsExpected")
                .given(newKeyNamed("supplyKey"), tokenCreate("t").supplyKey("supplyKey"))
                .when()
                .then(
                        submitModified(withSuccessivelyVariedBodyIds(), () -> mintToken("t", 123L)),
                        submitModified(withSuccessivelyVariedBodyIds(), () -> burnToken("t", 123L)));
    }

    @HapiTest
    final Stream<DynamicTest> freezeMgmtSuccessCasesWork() {
        var withPlusDefaultFalse = "withPlusDefaultFalse";

        return defaultHapiSpec("FreezeMgmtSuccessCasesWork")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate("misc").balance(0L),
                        newKeyNamed("oneFreeze"),
                        newKeyNamed("twoFreeze"),
                        tokenCreate(withPlusDefaultFalse)
                                .freezeDefault(false)
                                .freezeKey("twoFreeze")
                                .treasury(TOKEN_TREASURY),
                        tokenAssociate("misc", withPlusDefaultFalse))
                .when(
                        cryptoTransfer(moving(1, withPlusDefaultFalse).between(TOKEN_TREASURY, "misc")),
                        tokenFreeze(withPlusDefaultFalse, "misc"),
                        cryptoTransfer(moving(1, withPlusDefaultFalse).between(TOKEN_TREASURY, "misc"))
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        getAccountInfo("misc").logged(),
                        tokenUnfreeze(withPlusDefaultFalse, "misc"),
                        cryptoTransfer(moving(1, withPlusDefaultFalse).between(TOKEN_TREASURY, "misc")))
                .then(getAccountInfo("misc").logged());
    }

    @HapiTest
    final Stream<DynamicTest> kycMgmtSuccessCasesWork() {
        var withKycKey = "withKycKey";
        var withoutKycKey = "withoutKycKey";

        return defaultHapiSpec("KycMgmtSuccessCasesWork")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate("misc").balance(0L),
                        newKeyNamed(ONE_KYC),
                        newKeyNamed("twoKyc"),
                        tokenCreate(withKycKey).kycKey(ONE_KYC).treasury(TOKEN_TREASURY),
                        tokenCreate(withoutKycKey).treasury(TOKEN_TREASURY),
                        tokenAssociate("misc", withKycKey, withoutKycKey))
                .when(
                        cryptoTransfer(moving(1, withKycKey).between(TOKEN_TREASURY, "misc"))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
                        getAccountInfo("misc").logged(),
                        grantTokenKyc(withKycKey, "misc"),
                        cryptoTransfer(moving(1, withKycKey).between(TOKEN_TREASURY, "misc")),
                        revokeTokenKyc(withKycKey, "misc"),
                        cryptoTransfer(moving(1, withKycKey).between(TOKEN_TREASURY, "misc"))
                                .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN),
                        cryptoTransfer(moving(1, withoutKycKey).between(TOKEN_TREASURY, "misc")))
                .then(getAccountInfo("misc").logged());
    }

    @HapiTest
    final Stream<DynamicTest> supplyMgmtSuccessCasesWork() {
        return defaultHapiSpec("SupplyMgmtSuccessCasesWork")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(SUPPLE)
                                .supplyKey(SUPPLY_KEY)
                                .initialSupply(10)
                                .decimals(1)
                                .treasury(TOKEN_TREASURY))
                .when(
                        getTokenInfo(SUPPLE).logged(),
                        getAccountBalance(TOKEN_TREASURY).logged(),
                        mintToken(SUPPLE, 100).via(MINT_TXN),
                        burnToken(SUPPLE, 50).via("burnTxn"))
                .then(
                        getAccountInfo(TOKEN_TREASURY).logged(),
                        getTokenInfo(SUPPLE).logged(),
                        getTxnRecord(MINT_TXN).logged(),
                        getTxnRecord("burnTxn").logged());
    }

    @HapiTest
    final Stream<DynamicTest> fungibleCommonMaxSupplyReachWork() {
        return defaultHapiSpec("FungibleCommonMaxSupplyReachWork")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .initialSupply(0)
                                .maxSupply(500)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.FINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(FUNGIBLE_TOKEN, 3000)
                        .hasKnownStatus(TOKEN_MAX_SUPPLY_REACHED)
                        .via(SHOULD_NOT_APPEAR))
                .then(
                        getTxnRecord(SHOULD_NOT_APPEAR).showsNoTransfers(),
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        withOpContext((spec, opLog) -> {
                            var mintNFT = getTxnRecord(SHOULD_NOT_APPEAR);
                            allRunFor(spec, mintNFT);
                            var receipt = mintNFT.getResponseRecord().getReceipt();
                            Assertions.assertEquals(0, receipt.getNewTotalSupply());
                        }));
    }

    @HapiTest
    final Stream<DynamicTest> mintingMaxLongValueWorks() {
        return defaultHapiSpec("MintingMaxLongValueWorks")
                .given(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(TOKEN_TREASURY).balance(10L),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .initialSupply(0)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(FUNGIBLE_TOKEN, Long.MAX_VALUE).via(MINT_TXN))
                .then(getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, Long.MAX_VALUE));
    }

    @HapiTest
    final Stream<DynamicTest> nftMintProvidesMintedNftsAndNewTotalSupply() {
        final var multiKey = "multi";
        final var token = "non-fungible";
        final var txn = "mint";
        return defaultHapiSpec("NftMintProvidesMintedNftsAndNewTotalSupply")
                .given(
                        newKeyNamed(multiKey),
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        tokenCreate(token)
                                .initialSupply(0)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.INFINITE)
                                .supplyKey(multiKey)
                                .treasury(TOKEN_TREASURY))
                .when(mintToken(
                                token,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(txn))
                .then(getTxnRecord(txn)
                        .hasPriority(recordWith().newTotalSupply(3L).serialNos(List.of(1L, 2L, 3L)))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> supplyMgmtFailureCasesWork() {
        return defaultHapiSpec("SupplyMgmtFailureCasesWork")
                .given(newKeyNamed(SUPPLY_KEY))
                .when(
                        tokenCreate(RIGID),
                        tokenCreate(SUPPLE).supplyKey(SUPPLY_KEY).decimals(16).initialSupply(1))
                .then(
                        mintToken(RIGID, 1).signedBy(GENESIS).hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                        burnToken(RIGID, 1).signedBy(GENESIS).hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY),
                        mintToken(SUPPLE, Long.MAX_VALUE).hasKnownStatus(INVALID_TOKEN_MINT_AMOUNT),
                        mintToken(SUPPLE, 0).hasPrecheck(OK),
                        mintToken(SUPPLE, -1).hasPrecheck(INVALID_TOKEN_MINT_AMOUNT),
                        burnToken(SUPPLE, 2).hasKnownStatus(INVALID_TOKEN_BURN_AMOUNT),
                        burnToken(SUPPLE, 0).hasPrecheck(OK),
                        burnToken(SUPPLE, -1).hasPrecheck(INVALID_TOKEN_BURN_AMOUNT));
    }

    @HapiTest
    final Stream<DynamicTest> requireCorrectSupplyKeys() {
        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(TOKEN),
                tokenCreate(TOKEN).supplyKey(SUPPLY_KEY).initialSupply(1),
                // mint without the supply key
                mintToken(TOKEN, 1).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // mint with the supply key
                mintToken(TOKEN, 1).signedBy(GENESIS, SUPPLY_KEY).hasKnownStatus(SUCCESS),
                // burn without the supply key
                burnToken(TOKEN, 1).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE),
                // burn with the supply key
                burnToken(TOKEN, 1).signedBy(GENESIS, SUPPLY_KEY).hasKnownStatus(SUCCESS));
    }
}
