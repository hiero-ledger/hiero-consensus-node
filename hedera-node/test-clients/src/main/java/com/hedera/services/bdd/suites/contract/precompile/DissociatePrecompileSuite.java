// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ED25519_ON;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.contract.Utils.idAsHeadlongAddress;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.FREEZABLE_TOKEN_ON_BY_DEFAULT;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.KNOWABLE_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.TBD_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class DissociatePrecompileSuite {
    private static final String NEGATIVE_DISSOCIATIONS_CONTRACT = "NegativeDissociationsContract";
    private static final long GAS_TO_OFFER = 4_000_000L;
    private static final String ACCOUNT = "anybody";
    private static final String TOKEN = "Token";
    private static final String TOKEN1 = "Token1";
    private static final String CONTRACT_KEY = "ContractKey";
    private static final String THRESHOLD_KEY = "THRESHOLD_KEY";
    private static final String OUTER_CONTRACT = "NestedAssociateDissociate";
    private static final String NESTED_CONTRACT = "AssociateDissociate";
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String CONTRACT_AD = "AssociateDissociate";
    private static final String CONTRACT_ID_KEY = "CONTRACT_ID_KEY";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final KeyShape THRESHOLD_KEY_SHAPE_2_CONTRACTS = KeyShape.threshOf(1, ED25519, CONTRACT, CONTRACT);
    private static final String CONTRACT_KEY_NESTED = "CONTRACT_KEY_NESTED";

    @HapiTest
    final Stream<DynamicTest> dissociateTokensNegativeScenarios() {
        final AtomicReference<Address> tokenAddress1 = new AtomicReference<>();
        final AtomicReference<Address> tokenAddress2 = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final var nonExistingAccount = "nonExistingAccount";
        final var nonExistingTokenArray = "nonExistingTokenArray";
        final var someNonExistingTokenArray = "someNonExistingTokenArray";
        final var zeroAccountAddress = "zeroAccountAddress";
        final var nullTokenArray = "nullTokens";
        final var nonExistingTokensInArray = "nonExistingTokensInArray";
        return hapiTest(
                uploadInitCode(NEGATIVE_DISSOCIATIONS_CONTRACT),
                contractCreate(NEGATIVE_DISSOCIATIONS_CONTRACT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress1::set),
                tokenCreate(TOKEN1)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress2::set),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(id -> accountAddress.set(idAsHeadlongAddress(id))),
                tokenAssociate(ACCOUNT, List.of(TOKEN, TOKEN1)),
                withOpContext((spec, custom) -> allRunFor(
                        spec,
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokensWithNonExistingAccountAddress",
                                        (Object) new Address[] {tokenAddress1.get(), tokenAddress2.get()})
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(nonExistingAccount)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(TOKEN1)),
                        newKeyNamed(CONTRACT_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, NEGATIVE_DISSOCIATIONS_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokensWithEmptyTokensArray",
                                        accountAddress.get())
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER)
                                .signingWith(ACCOUNT)
                                .via(nonExistingTokenArray)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(TOKEN1)),
                        contractCall(NEGATIVE_DISSOCIATIONS_CONTRACT, "dissociateTokensWithNullAccount", (Object)
                                        new Address[] {tokenAddress1.get(), tokenAddress2.get()})
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(zeroAccountAddress)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(TOKEN1)),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokensWithNullTokensArray",
                                        accountAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .signingWith(ACCOUNT)
                                .via(nullTokenArray)
                                .logged(),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokensWithNonExistingTokensArray",
                                        accountAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .signingWith(ACCOUNT)
                                .via(nonExistingTokensInArray)
                                .logged(),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokensWithTokensArrayWithSomeNonExistingAddresses",
                                        accountAddress.get(),
                                        new Address[] {tokenAddress1.get(), tokenAddress2.get()})
                                .hasKnownStatus(SUCCESS)
                                .gas(GAS_TO_OFFER)
                                .signingWith(ACCOUNT)
                                .via(someNonExistingTokenArray)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(TOKEN),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(TOKEN1))),
                childRecordsCheck(
                        nonExistingAccount,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(nonExistingTokenArray, SUCCESS, recordWith().status(SUCCESS)),
                childRecordsCheck(
                        zeroAccountAddress,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        nullTokenArray, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)),
                childRecordsCheck(
                        someNonExistingTokenArray, SUCCESS, recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> dissociateTokenNegativeScenarios() {
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final var nonExistingAccount = "nonExistingAccount";
        final var nullAccount = "nullAccount";
        final var nonExistingToken = "nonExistingToken";
        final var nullToken = "nullToken";
        return hapiTest(
                uploadInitCode(NEGATIVE_DISSOCIATIONS_CONTRACT),
                contractCreate(NEGATIVE_DISSOCIATIONS_CONTRACT),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(id -> accountAddress.set(idAsHeadlongAddress(id))),
                withOpContext((spec, custom) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, NEGATIVE_DISSOCIATIONS_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokenWithNonExistingAccount",
                                        tokenAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(nonExistingAccount)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(TOKEN),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokenWithNullAccount",
                                        tokenAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(nullAccount)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(TOKEN),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokenWithNonExistingTokenAddress",
                                        accountAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(nonExistingToken)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(TOKEN),
                        contractCall(
                                        NEGATIVE_DISSOCIATIONS_CONTRACT,
                                        "dissociateTokenWithNullTokenAddress",
                                        accountAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER)
                                .via(nullToken)
                                .logged(),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(TOKEN))),
                childRecordsCheck(
                        nonExistingAccount,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        nullAccount, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_ACCOUNT_ID)),
                childRecordsCheck(
                        nullToken, CONTRACT_REVERT_EXECUTED, recordWith().status(INVALID_TOKEN_ID)));
    }

    /* -- Not specifically required in the HTS Precompile Test Plan -- */
    @HapiTest
    public Stream<DynamicTest> dissociatePrecompileHasExpectedSemanticsForDeletedTokens() {
        final var tbdUniqToken = "UniqToBeDeleted";
        final var zeroBalanceFrozen = "0bFrozen";
        final var zeroBalanceUnfrozen = "0bUnfrozen";
        final var nonZeroBalanceFrozen = "1bFrozen";
        final var nonZeroBalanceUnfrozen = "1bUnfrozen";
        final var initialSupply = 100L;
        final var nonZeroXfer = 10L;
        final var firstMeta = ByteString.copyFrom("FIRST".getBytes(StandardCharsets.UTF_8));
        final var secondMeta = ByteString.copyFrom("SECOND".getBytes(StandardCharsets.UTF_8));
        final var thirdMeta = ByteString.copyFrom("THIRD".getBytes(StandardCharsets.UTF_8));
        final var ASSOCIATE_DISSOCIATE_CONTRACT = "AssociateDissociate";

        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> zeroBalanceFrozenID = new AtomicReference<>();
        final AtomicReference<AccountID> zeroBalanceUnfrozenID = new AtomicReference<>();
        final AtomicReference<AccountID> nonZeroBalanceFrozenID = new AtomicReference<>();
        final AtomicReference<AccountID> nonZeroBalanceUnfrozenID = new AtomicReference<>();
        final AtomicReference<TokenID> tbdTokenID = new AtomicReference<>();
        final AtomicReference<TokenID> tbdUniqueTokenID = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT).gas(GAS_TO_OFFER),
                cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS).exposingCreatedIdTo(accountID::set),
                cryptoCreate(zeroBalanceFrozen)
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(zeroBalanceFrozenID::set),
                cryptoCreate(zeroBalanceUnfrozen)
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(zeroBalanceUnfrozenID::set),
                cryptoCreate(nonZeroBalanceFrozen)
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(nonZeroBalanceFrozenID::set),
                cryptoCreate(nonZeroBalanceUnfrozen)
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(nonZeroBalanceUnfrozenID::set),
                newKeyNamed(THRESHOLD_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ED25519_ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                cryptoUpdate(zeroBalanceFrozen).key(THRESHOLD_KEY),
                cryptoUpdate(zeroBalanceUnfrozen).key(THRESHOLD_KEY),
                cryptoUpdate(nonZeroBalanceFrozen).key(THRESHOLD_KEY),
                cryptoUpdate(nonZeroBalanceUnfrozen).key(THRESHOLD_KEY),
                cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                tokenCreate(TBD_TOKEN)
                        .adminKey(THRESHOLD_KEY)
                        .supplyKey(THRESHOLD_KEY)
                        .initialSupply(initialSupply)
                        .treasury(ACCOUNT)
                        .freezeKey(THRESHOLD_KEY)
                        .freezeDefault(true)
                        .exposingCreatedIdTo(id -> tbdTokenID.set(asToken(id))),
                tokenCreate(tbdUniqToken)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .treasury(ACCOUNT)
                        .adminKey(THRESHOLD_KEY)
                        .supplyKey(THRESHOLD_KEY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> tbdUniqueTokenID.set(asToken(id))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        tokenAssociate(zeroBalanceFrozen, TBD_TOKEN),
                        tokenAssociate(zeroBalanceUnfrozen, TBD_TOKEN),
                        tokenAssociate(nonZeroBalanceFrozen, TBD_TOKEN),
                        tokenAssociate(nonZeroBalanceUnfrozen, TBD_TOKEN),
                        mintToken(tbdUniqToken, List.of(firstMeta, secondMeta, thirdMeta)),
                        getAccountInfo(ACCOUNT).hasOwnedNfts(3),
                        tokenUnfreeze(TBD_TOKEN, zeroBalanceUnfrozen),
                        tokenUnfreeze(TBD_TOKEN, nonZeroBalanceUnfrozen),
                        tokenUnfreeze(TBD_TOKEN, nonZeroBalanceFrozen),
                        cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN).between(ACCOUNT, nonZeroBalanceFrozen)),
                        cryptoTransfer(moving(nonZeroXfer, TBD_TOKEN).between(ACCOUNT, nonZeroBalanceUnfrozen)),
                        tokenFreeze(TBD_TOKEN, nonZeroBalanceFrozen),
                        getAccountBalance(ACCOUNT).hasTokenBalance(TBD_TOKEN, initialSupply - 2 * nonZeroXfer),
                        tokenDelete(TBD_TOKEN),
                        tokenDelete(tbdUniqToken),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        "tokenDissociate",
                                        asHeadlongAddress(asAddress(zeroBalanceFrozenID.get())),
                                        asHeadlongAddress(asAddress(tbdTokenID.get())))
                                .payingWith(ACCOUNT)
                                .signedBy(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("dissociateZeroBalanceFrozenTxn"),
                        getTxnRecord("dissociateZeroBalanceFrozenTxn")
                                .andAllChildRecords()
                                .logged(),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        "tokenDissociate",
                                        asHeadlongAddress(asAddress(zeroBalanceUnfrozenID.get())),
                                        asHeadlongAddress(asAddress(tbdTokenID.get())))
                                .payingWith(ACCOUNT)
                                .signedBy(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("dissociateZeroBalanceUnfrozenTxn"),
                        getTxnRecord("dissociateZeroBalanceUnfrozenTxn")
                                .andAllChildRecords()
                                .logged(),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        "tokenDissociate",
                                        asHeadlongAddress(asAddress(nonZeroBalanceFrozenID.get())),
                                        asHeadlongAddress(asAddress(tbdTokenID.get())))
                                .payingWith(ACCOUNT)
                                .signedBy(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("dissociateNonZeroBalanceFrozenTxn"),
                        getTxnRecord("dissociateNonZeroBalanceFrozenTxn")
                                .andAllChildRecords()
                                .logged(),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        "tokenDissociate",
                                        asHeadlongAddress(asAddress(nonZeroBalanceUnfrozenID.get())),
                                        asHeadlongAddress(asAddress(tbdTokenID.get())))
                                .payingWith(ACCOUNT)
                                .signedBy(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("dissociateNonZeroBalanceUnfrozenTxn"),
                        getTxnRecord("dissociateNonZeroBalanceUnfrozenTxn")
                                .andAllChildRecords()
                                .logged(),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        "tokenDissociate",
                                        asHeadlongAddress(asAddress(accountID.get())),
                                        asHeadlongAddress(asAddress(tbdUniqueTokenID.get())))
                                .payingWith(ACCOUNT)
                                .signedBy(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "dissociateZeroBalanceFrozenTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "dissociateZeroBalanceUnfrozenTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "dissociateNonZeroBalanceFrozenTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "dissociateNonZeroBalanceUnfrozenTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(zeroBalanceFrozen).hasNoTokenRelationship(TBD_TOKEN),
                getAccountInfo(zeroBalanceUnfrozen).hasNoTokenRelationship(TBD_TOKEN),
                getAccountInfo(nonZeroBalanceFrozen).hasNoTokenRelationship(TBD_TOKEN),
                getAccountInfo(nonZeroBalanceUnfrozen).hasNoTokenRelationship(TBD_TOKEN),
                getAccountInfo(ACCOUNT)
                        .hasToken(relationshipWith(TBD_TOKEN))
                        .hasNoTokenRelationship(tbdUniqToken)
                        .hasOwnedNfts(0),
                getAccountBalance(ACCOUNT).hasTokenBalance(TBD_TOKEN, initialSupply - 2 * nonZeroXfer));
    }

    @HapiTest
    final Stream<DynamicTest> nestedDissociateWorksAsExpected() {
        final var NESTED_DISSOCIATE_FUNGIBLE_TXN = "nestedDissociateFungibleTxn";
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN).tokenType(FUNGIBLE_COMMON).treasury(TOKEN_TREASURY),
                uploadInitCode(OUTER_CONTRACT, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                OUTER_CONTRACT, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        // Test Case: Account paying and signing a nested fungible TOKEN DISSOCIATE TRANSACTION,
                        // when we Dissociate the token to the signer
                        //  → call → CONTRACT A → call → CONTRACT B → call → PRECOMPILE(HTS)
                        newKeyNamed(CONTRACT_KEY_NESTED)
                                .shape(THRESHOLD_KEY_SHAPE_2_CONTRACTS.signedWith(
                                        sigs(ON, NESTED_CONTRACT, OUTER_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY_NESTED),
                        contractCall(
                                        OUTER_CONTRACT,
                                        "associateDissociateContractCall",
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(VANILLA_TOKEN))))
                                .payingWith(ACCOUNT)
                                .hasRetryPrecheckFrom(BUSY)
                                .via(NESTED_DISSOCIATE_FUNGIBLE_TXN)
                                .gas(GAS_TO_OFFER))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    public Stream<DynamicTest> multiplePrecompileDissociationWithSigsForFungibleWorks() {
        final AtomicReference<TokenID> knowableTokenTokenID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(CONTRACT_AD),
                contractCreate(CONTRACT_AD),
                newKeyNamed(CONTRACT_ID_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, CONTRACT_AD))),
                cryptoCreate(ACCOUNT)
                        .balance(10 * ONE_HUNDRED_HBARS)
                        .key(CONTRACT_ID_KEY)
                        .exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).balance(0L).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                tokenCreate(KNOWABLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .exposingCreatedIdTo(id -> knowableTokenTokenID.set(asToken(id))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        tokenAssociate(ACCOUNT, List.of(VANILLA_TOKEN, KNOWABLE_TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)),
                        getAccountInfo(ACCOUNT).hasToken(relationshipWith(KNOWABLE_TOKEN)),
                        contractCall(
                                        CONTRACT_AD,
                                        "tokensDissociate",
                                        asHeadlongAddress(asAddress(accountID.get())),
                                        new Address[] {
                                            asHeadlongAddress(asAddress(vanillaTokenID.get())),
                                            asHeadlongAddress(asAddress(knowableTokenTokenID.get()))
                                        })
                                .payingWith(ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via("multipleDissociationTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        getTxnRecord("multipleDissociationTxn")
                                .andAllChildRecords()
                                .logged())),
                childRecordsCheck(
                        "multipleDissociationTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(FREEZABLE_TOKEN_ON_BY_DEFAULT),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(KNOWABLE_TOKEN));
    }
}
