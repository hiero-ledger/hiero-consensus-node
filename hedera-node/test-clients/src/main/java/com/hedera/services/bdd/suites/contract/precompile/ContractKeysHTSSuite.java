// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.LONG_RUNNING;
import static com.hedera.services.bdd.spec.HapiPropertySource.asDotDelimitedLongArray;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_STILL_OWNS_NFTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.assertions.NonFungibleTransfers;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(LONG_RUNNING)
public class ContractKeysHTSSuite {

    private static final long GAS_TO_OFFER = 1_500_000L;

    private static final String TOKEN_TREASURY = "treasury";
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String NFT = "nft";

    private static final String ACCOUNT = "sender";
    private static final String RECEIVER = "receiver";

    private static final KeyShape CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, SIMPLE, KeyShape.CONTRACT);
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);

    private static final String UNIVERSAL_KEY = "Multipurpose";
    private static final String DELEGATE_KEY = "Delegate Contract Key";
    private static final String CONTRACT_KEY = "Contract Key";
    private static final String FROZEN_TOKEN = "Frozen Token";
    private static final String KYC_TOKEN = "KYC Token";
    private static final String FREEZE_KEY = "Freeze Key";
    private static final String KYC_KEY = "KYC Key";
    private static final String MULTI_KEY = "Multi Key";
    private static final String SUPPLY_KEY = "Supply Key";

    private static final String ORDINARY_CALLS_CONTRACT = "HTSCalls";
    private static final String ASSOCIATE_DISSOCIATE_CONTRACT = "AssociateDissociate";
    private static final String BURN_TOKEN = "BurnToken";
    private static final String BURN_TOKEN_METHOD = "burnToken";
    private static final String STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN = "staticBurnCallWithContractKeyTxn";
    private static final String STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN =
            "staticBurnCallWithDelegateContractKeyTxn";
    private static final String NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN =
            "nonZeroTokenBalanceDissociateWithDelegateContractKeyFailedTxn";
    private static final String TOKEN_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_HAPPY_TXN =
            "tokenDissociateWithDelegateContractKeyHappyTxn";
    private static final String CREATION_TX = "creationTx";
    private static final String DISTRIBUTE_TX = "distributeTx";
    private static final String TOKEN_ASSOCIATE = "tokenAssociate";
    private static final String TOKEN_DISSOCIATE = "tokenDissociate";
    private static final String BURN_WITH_CONTRACT_KEY = "burn with contract key";
    private static final String VANILLA_TOKEN_ASSOCIATE_TXN = "vanillaTokenAssociateTxn";
    private static final String TOKEN_USAGE = "Token";
    private static final String OUTER_CONTRACT = "DelegateContract";
    private static final String NESTED_CONTRACT = "ServiceContract";
    private static final String SECOND_STR_FOR_MINT = "Second!";
    private static final String DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN = "delegateBurnCallWithContractKeyTxn";
    private static final String NESTED_ASSOCIATE_DISSOCIATE = "NestedAssociateDissociate";
    private static final String STATIC_CONTRACT = "StaticContract";
    private static final String FIRST_STRING_FOR_MINT = "First!";
    private static final String ACCOUNT_NAME = "anybody";
    private static final String TYPE_OF_TOKEN = "fungibleToken";

    @HapiTest
    final Stream<DynamicTest> burnWithKeyAsPartOf1OfXThreshold() {
        final var delegateContractKeyShape = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var contractKeyShape = KeyShape.threshOf(1, SIMPLE, KeyShape.CONTRACT);

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TOKEN_USAGE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(BURN_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        BURN_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(TOKEN_USAGE))))
                                .via(CREATION_TX))),
                newKeyNamed(DELEGATE_KEY).shape(delegateContractKeyShape.signedWith(sigs(ON, BURN_TOKEN))),
                tokenUpdate(TOKEN_USAGE).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                        .via("burn with delegate contract key")
                        .gas(GAS_TO_OFFER),
                childRecordsCheck(
                        "burn with delegate contract key",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN_USAGE, 49),
                newKeyNamed(CONTRACT_KEY).shape(contractKeyShape.signedWith(sigs(ON, BURN_TOKEN))),
                tokenUpdate(TOKEN_USAGE).supplyKey(CONTRACT_KEY).signedByPayerAnd(MULTI_KEY),
                contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                        .via(BURN_WITH_CONTRACT_KEY)
                        .gas(GAS_TO_OFFER),
                childRecordsCheck(
                        BURN_WITH_CONTRACT_KEY,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(48)))
                                .tokenTransfers(
                                        changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallForBurnWithContractKey() {
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(SECOND_STR_FOR_MINT))),
                uploadInitCode(OUTER_CONTRACT, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                OUTER_CONTRACT, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, OUTER_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        OUTER_CONTRACT,
                                        "burnDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ZERO,
                                        new long[] {1L})
                                .payingWith(GENESIS)
                                .via(DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_BURN)
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(VANILLA_TOKEN, 2));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallForMintWithContractKey() {
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(50L)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(OUTER_CONTRACT, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                OUTER_CONTRACT, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, OUTER_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        OUTER_CONTRACT,
                                        "mintDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ONE)
                                .payingWith(GENESIS)
                                .via(DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_MINT)
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                                .withSerialNumbers()))),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(VANILLA_TOKEN, 50));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForDissociatePrecompileFails() {
        final var outerContract = NESTED_ASSOCIATE_DISSOCIATE;
        final var nestedContract = ASSOCIATE_DISSOCIATE_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, nestedContract),
                contractCreate(nestedContract),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(nestedContract, spec))),
                        contractCall(
                                        outerContract,
                                        "dissociateStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("staticDissociateCallTxn")
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck("staticDissociateCallTxn", CONTRACT_REVERT_EXECUTED),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForTransferWithContractKey() {
        final var outerContract = STATIC_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> receiverID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverID::set),
                uploadInitCode(outerContract, NESTED_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(NESTED_CONTRACT).refusingEthConversion(),
                tokenAssociate(NESTED_CONTRACT, VANILLA_TOKEN),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT))
                        .payingWith(GENESIS),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        outerContract,
                                        asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec)))
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion(),
                        tokenAssociate(outerContract, VANILLA_TOKEN),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        outerContract,
                                        "transferStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(receiverID.get())),
                                        1L)
                                .payingWith(GENESIS)
                                .via("staticTransferCallWithContractKeyTxn")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck("staticTransferCallWithContractKeyTxn", CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForBurnWithContractKey() {
        final var outerContract = STATIC_CONTRACT;
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(SECOND_STR_FOR_MINT))),
                uploadInitCode(outerContract, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        outerContract,
                                        "burnStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ZERO,
                                        new long[] {1L})
                                .payingWith(GENESIS)
                                .via(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForMintWithContractKey() {
        final var outerContract = STATIC_CONTRACT;
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(50L)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        outerContract,
                                        "mintStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ONE)
                                .payingWith(GENESIS)
                                .via(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForTransferWithDelegateContractKey() {
        final var outerContract = STATIC_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> receiverID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverID::set),
                uploadInitCode(outerContract, NESTED_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(NESTED_CONTRACT).refusingEthConversion(),
                tokenAssociate(NESTED_CONTRACT, VANILLA_TOKEN),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT))
                        .payingWith(GENESIS),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        outerContract,
                                        asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec)))
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion(),
                        tokenAssociate(outerContract, VANILLA_TOKEN),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        outerContract,
                                        "transferStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(receiverID.get())),
                                        1L)
                                .payingWith(GENESIS)
                                .via("staticTransferCallWithDelegateContractKeyTxn")
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck("staticTransferCallWithDelegateContractKeyTxn", CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForBurnWithDelegateContractKey() {
        final var outerContract = STATIC_CONTRACT;
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(SECOND_STR_FOR_MINT))),
                uploadInitCode(outerContract, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        outerContract,
                                        "burnStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ZERO,
                                        new long[] {1L})
                                .payingWith(GENESIS)
                                .via(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForMintWithDelegateContractKey() {
        final var outerContract = STATIC_CONTRACT;
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(50L)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        outerContract,
                                        "mintStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ONE)
                                .payingWith(GENESIS)
                                .via(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> staticCallForAssociatePrecompileFails() {
        final var outerContract = NESTED_ASSOCIATE_DISSOCIATE;
        final var nestedContract = ASSOCIATE_DISSOCIATE_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT)
                        .balance(ONE_MILLION_HBARS)
                        .payingWith(GENESIS)
                        .exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, nestedContract),
                contractCreate(nestedContract),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(nestedContract, spec))),
                        contractCall(
                                        outerContract,
                                        "associateStaticCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())))
                                .payingWith(ACCOUNT)
                                .via("staticAssociateCallTxn")
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                emptyChildRecordsCheck("staticAssociateCallTxn", CONTRACT_REVERT_EXECUTED),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> callForMintWithContractKey() {
        final var firstMintTxn = "firstMintTxn";
        final var amount = 10L;

        final AtomicLong fungibleNum = new AtomicLong();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT_NAME).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TYPE_OF_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(idLit -> fungibleNum.set(asDotDelimitedLongArray(idLit)[2])),
                uploadInitCode(ORDINARY_CALLS_CONTRACT),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY)
                                .shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                        tokenUpdate(TYPE_OF_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(TYPE_OF_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(firstMintTxn)
                                .payingWith(ACCOUNT_NAME))),
                childRecordsCheck(
                        firstMintTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_MINT)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(10)
                                                .withSerialNumbers()))
                                .tokenTransfers(changingFungibleBalances().including(TYPE_OF_TOKEN, TOKEN_TREASURY, 10))
                                .newTotalSupply(10)),
                getTokenInfo(TYPE_OF_TOKEN).hasTotalSupply(amount),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TYPE_OF_TOKEN, amount));
    }

    @HapiTest
    final Stream<DynamicTest> callForMintWithDelegateContractKey() {
        final var firstMintTxn = "firstMintTxn";
        final var amount = 10L;

        final AtomicLong fungibleNum = new AtomicLong();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT_NAME).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TYPE_OF_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(idLit -> fungibleNum.set(asDotDelimitedLongArray(idLit)[2])),
                uploadInitCode(ORDINARY_CALLS_CONTRACT),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                        tokenUpdate(TYPE_OF_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(TYPE_OF_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(firstMintTxn)
                                .payingWith(ACCOUNT_NAME))),
                childRecordsCheck(
                        firstMintTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_MINT)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(10)
                                                .withSerialNumbers()))
                                .tokenTransfers(changingFungibleBalances().including(TYPE_OF_TOKEN, TOKEN_TREASURY, 10))
                                .newTotalSupply(10)),
                getTokenInfo(TYPE_OF_TOKEN).hasTotalSupply(amount),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TYPE_OF_TOKEN, amount));
    }

    @HapiTest
    final Stream<DynamicTest> callForTransferWithContractKey() {
        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(UNIVERSAL_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ACCOUNT, NFT),
                mintToken(NFT, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                uploadInitCode(ORDINARY_CALLS_CONTRACT),
                contractCreate(ORDINARY_CALLS_CONTRACT)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .via(CREATION_TX),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY)
                                .shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        tokenAssociate(ORDINARY_CALLS_CONTRACT, List.of(NFT)),
                        tokenAssociate(RECEIVER, List.of(NFT)),
                        cryptoTransfer(movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "transferNFTCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        1L)
                                .fee(2 * ONE_HBAR)
                                .hasKnownStatus(SUCCESS)
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .via(DISTRIBUTE_TX))),
                getTokenInfo(NFT).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT, 1),
                getAccountInfo(ACCOUNT).hasOwnedNfts(0),
                getAccountBalance(ACCOUNT).hasTokenBalance(NFT, 0),
                childRecordsCheck(
                        DISTRIBUTE_TX,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT, ACCOUNT, RECEIVER, 1L))));
    }

    @HapiTest
    final Stream<DynamicTest> callForTransferWithDelegateContractKey() {
        return hapiTest(
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(UNIVERSAL_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ACCOUNT, NFT),
                mintToken(NFT, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                uploadInitCode(ORDINARY_CALLS_CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(ORDINARY_CALLS_CONTRACT).refusingEthConversion(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        tokenAssociate(ORDINARY_CALLS_CONTRACT, List.of(NFT)),
                        tokenAssociate(RECEIVER, List.of(NFT)),
                        cryptoTransfer(movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "transferNFTCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NFT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(ACCOUNT))),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(RECEIVER))),
                                        1L)
                                .fee(2 * ONE_HBAR)
                                .hasKnownStatus(SUCCESS)
                                .payingWith(GENESIS)
                                .gas(GAS_TO_OFFER)
                                .via(DISTRIBUTE_TX))),
                getTokenInfo(NFT).hasTotalSupply(2),
                getAccountInfo(RECEIVER).hasOwnedNfts(1),
                getAccountBalance(RECEIVER).hasTokenBalance(NFT, 1),
                getAccountInfo(ACCOUNT).hasOwnedNfts(0),
                getAccountBalance(ACCOUNT).hasTokenBalance(NFT, 0),
                childRecordsCheck(
                        DISTRIBUTE_TX,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))
                                .tokenTransfers(NonFungibleTransfers.changingNFTBalances()
                                        .including(NFT, ACCOUNT, RECEIVER, 1L))));
    }

    @HapiTest
    final Stream<DynamicTest> callForAssociateWithDelegateContractKey() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(VANILLA_TOKEN_ASSOCIATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        VANILLA_TOKEN_ASSOCIATE_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> callForAssociateWithContractKey() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY)
                                .shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(VANILLA_TOKEN_ASSOCIATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        VANILLA_TOKEN_ASSOCIATE_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> callForDissociateWithDelegateContractKey() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final var totalSupply = 1_000;

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(totalSupply)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        cryptoUpdate(TOKEN_TREASURY).key(DELEGATE_KEY),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(TOKEN_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_HAPPY_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)))),
                childRecordsCheck(
                        TOKEN_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_HAPPY_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> callForDissociateWithContractKey() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final var totalSupply = 1_000;

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(totalSupply)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY)
                                .shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        cryptoUpdate(TOKEN_TREASURY).key(CONTRACT_KEY),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("nonZeroTokenBalanceDissociateWithContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("tokenDissociateWithContractKeyHappyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "nonZeroTokenBalanceDissociateWithContractKeyFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)))),
                childRecordsCheck(
                        "tokenDissociateWithContractKeyHappyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> callForBurnWithDelegateContractKey() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TOKEN_USAGE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(BURN_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        BURN_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(TOKEN_USAGE))))
                                .via(CREATION_TX))),
                newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, BURN_TOKEN))),
                tokenUpdate(TOKEN_USAGE).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                        .via(BURN_WITH_CONTRACT_KEY)
                        .gas(GAS_TO_OFFER),
                childRecordsCheck(
                        BURN_WITH_CONTRACT_KEY,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN_USAGE, 49));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallForAssociatePrecompileSignedWithDelegateContractKeyWorks() {
        final var outerContract = NESTED_ASSOCIATE_DISSOCIATE;
        final var nestedContract = ASSOCIATE_DISSOCIATE_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, nestedContract),
                contractCreate(nestedContract),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(nestedContract, spec))),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        outerContract,
                                        "associateDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("delegateAssociateCallWithDelegateContractKeyTxn")
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "delegateAssociateCallWithDelegateContractKeyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallForDissociatePrecompileSignedWithDelegateContractKeyWorks() {
        final var outerContract = NESTED_ASSOCIATE_DISSOCIATE;
        final var nestedContract = ASSOCIATE_DISSOCIATE_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, nestedContract),
                contractCreate(nestedContract),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(nestedContract, spec))),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        outerContract,
                                        "dissociateDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("delegateDissociateCallWithDelegateContractKeyTxn")
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "delegateDissociateCallWithDelegateContractKeyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForNonFungibleWithKYC() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> kycTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(KYC_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(KYC_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(GENESIS)
                        .kycKey(KYC_KEY)
                        .exposingCreatedIdTo(id -> kycTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycNFTAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycNFTAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycNFTSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "kycNFTAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "kycNFTAssociateTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "kycNFTSecondAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(KYC_TOKEN).kyc(Revoked)));
    }

    @HapiTest
    final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForFungibleVanilla() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        cryptoUpdate(TOKEN_TREASURY).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(treasuryID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("tokenDissociateFromTreasuryFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("tokenDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(TOKEN_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_HAPPY_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "tokenDissociateFromTreasuryFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_IS_TREASURY)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_IS_TREASURY)))),
                childRecordsCheck(
                        "tokenDissociateWithDelegateContractKeyFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)))),
                childRecordsCheck(
                        NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)))),
                childRecordsCheck(
                        TOKEN_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_HAPPY_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForFungibleFrozen() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> frozenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeDefault(true)
                        .freezeKey(FREEZE_KEY)
                        .exposingCreatedIdTo(id -> frozenTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        tokenAssociate(ACCOUNT, FROZEN_TOKEN),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenTokenAssociateWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        tokenUnfreeze(FROZEN_TOKEN, ACCOUNT),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("UnfrozenTokenAssociateWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "frozenTokenAssociateWithDelegateContractKeyTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_FROZEN_FOR_TOKEN)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_FROZEN_FOR_TOKEN)))),
                childRecordsCheck(
                        "UnfrozenTokenAssociateWithDelegateContractKeyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(FROZEN_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForFungibleWithKYC() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> kycTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(KYC_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(KYC_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .kycKey(KYC_KEY)
                        .exposingCreatedIdTo(id -> kycTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycTokenDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        tokenAssociate(ACCOUNT, KYC_TOKEN),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycTokenDissociateWithDelegateContractKeyHappyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "kycTokenDissociateWithDelegateContractKeyFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)))),
                childRecordsCheck(
                        "kycTokenDissociateWithDelegateContractKeyHappyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(KYC_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForNonFungibleVanilla() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).balance(0L).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                mintToken(VANILLA_TOKEN, List.of(metadata("memo"))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        cryptoUpdate(TOKEN_TREASURY).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(treasuryID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("NFTDissociateFromTreasuryFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("NFTDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoTransfer(movingUnique(VANILLA_TOKEN, 1).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("nonZeroNFTBalanceDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        cryptoTransfer(movingUnique(VANILLA_TOKEN, 1).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("NFTDissociateWithDelegateContractKeyHappyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "NFTDissociateFromTreasuryFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_IS_TREASURY)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_IS_TREASURY)))),
                childRecordsCheck(
                        "NFTDissociateWithDelegateContractKeyFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)))),
                childRecordsCheck(
                        "nonZeroNFTBalanceDissociateWithDelegateContractKeyFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_STILL_OWNS_NFTS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_STILL_OWNS_NFTS)))),
                childRecordsCheck(
                        "NFTDissociateWithDelegateContractKeyHappyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForNonFungibleFrozen() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> frozenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(GENESIS)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .freezeDefault(true)
                        .freezeKey(FREEZE_KEY)
                        .exposingCreatedIdTo(id -> frozenTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        tokenAssociate(ACCOUNT, FROZEN_TOKEN),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenNFTAssociateWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        tokenUnfreeze(FROZEN_TOKEN, ACCOUNT),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("UnfrozenNFTAssociateWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "frozenNFTAssociateWithDelegateContractKeyTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(ACCOUNT_FROZEN_FOR_TOKEN)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(ACCOUNT_FROZEN_FOR_TOKEN)))),
                childRecordsCheck(
                        "UnfrozenNFTAssociateWithDelegateContractKeyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(FROZEN_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForNonFungibleWithKYC() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<AccountID> treasuryID = new AtomicReference<>();
        final AtomicReference<TokenID> kycTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(KYC_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY).exposingCreatedIdTo(treasuryID::set),
                tokenCreate(KYC_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(GENESIS)
                        .initialSupply(0)
                        .kycKey(KYC_KEY)
                        .exposingCreatedIdTo(id -> kycTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycNFTDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        tokenAssociate(ACCOUNT, KYC_TOKEN),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycNFTDissociateWithDelegateContractKeyHappyTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                childRecordsCheck(
                        "kycNFTDissociateWithDelegateContractKeyFailedTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)))),
                childRecordsCheck(
                        "kycNFTDissociateWithDelegateContractKeyHappyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(KYC_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForNonFungibleFrozen() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> frozenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(GENESIS)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .exposingCreatedIdTo(id -> frozenTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenNFTAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenNFTAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenNFTSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "frozenNFTAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "frozenNFTAssociateTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "frozenNFTSecondAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(FROZEN_TOKEN).freeze(Frozen)));
    }

    @HapiTest
    final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForNonFungibleVanilla() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(GENESIS)
                        .initialSupply(0)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("vanillaNFTAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("vanillaNFTAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("vanillaNFTSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "vanillaNFTAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "vanillaNFTAssociateTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "vanillaNFTSecondAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForFungibleWithKYC() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> kycTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(KYC_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(KYC_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .kycKey(KYC_KEY)
                        .exposingCreatedIdTo(id -> kycTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycTokenAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycTokenAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(kycTokenID.get())))
                                .payingWith(GENESIS)
                                .via("kycTokenSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "kycTokenAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "kycTokenAssociateTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "kycTokenSecondAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(KYC_TOKEN).kyc(Revoked)));
    }

    @HapiTest
    final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForFungibleFrozen() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> frozenTokenID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(FREEZE_KEY),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .exposingCreatedIdTo(id -> frozenTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenTokenAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenTokenAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(frozenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("frozenTokenSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "frozenTokenAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        "frozenTokenAssociateTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "frozenTokenSecondAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(FROZEN_TOKEN).freeze(Frozen)));
    }

    @HapiTest
    final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForFungibleVanilla() {
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                uploadInitCode(ASSOCIATE_DISSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("vanillaTokenAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(DELEGATE_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via(VANILLA_TOKEN_ASSOCIATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .payingWith(GENESIS)
                                .via("vanillaTokenSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                childRecordsCheck(
                        "vanillaTokenAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        VANILLA_TOKEN_ASSOCIATE_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                childRecordsCheck(
                        "vanillaTokenSecondAssociateFailsTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallForAssociatePrecompileSignedWithContractKeyFails() {
        final var outerContract = NESTED_ASSOCIATE_DISSOCIATE;
        final var nestedContract = ASSOCIATE_DISSOCIATE_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, nestedContract),
                contractCreate(nestedContract),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(nestedContract, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        outerContract,
                                        "associateDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("delegateAssociateCallWithContractKeyTxn")
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "delegateAssociateCallWithContractKeyTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    final Stream<DynamicTest> delegateCallForDissociatePrecompileSignedWithContractKeyFails() {
        final var outerContract = NESTED_ASSOCIATE_DISSOCIATE;
        final var nestedContract = ASSOCIATE_DISSOCIATE_CONTRACT;
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final AtomicReference<TokenID> vanillaTokenTokenID = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> vanillaTokenTokenID.set(asToken(id))),
                uploadInitCode(outerContract, nestedContract),
                contractCreate(nestedContract),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                outerContract, asHeadlongAddress(getNestedContractAddress(nestedContract, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        contractCall(
                                        outerContract,
                                        "dissociateDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())))
                                .payingWith(GENESIS)
                                .via("delegateDissociateCallWithContractKeyTxn")
                                .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "delegateDissociateCallWithContractKeyTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    final Stream<DynamicTest> callForBurnWithContractKey() {
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(TOKEN_USAGE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(BURN_TOKEN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                        BURN_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(TOKEN_USAGE))))
                                .via(CREATION_TX))),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, BURN_TOKEN))),
                tokenUpdate(TOKEN_USAGE).supplyKey(CONTRACT_KEY).signedByPayerAnd(MULTI_KEY),
                contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                        .via(BURN_WITH_CONTRACT_KEY)
                        .gas(GAS_TO_OFFER),
                childRecordsCheck(
                        BURN_WITH_CONTRACT_KEY,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN_USAGE, 49));
    }
}
