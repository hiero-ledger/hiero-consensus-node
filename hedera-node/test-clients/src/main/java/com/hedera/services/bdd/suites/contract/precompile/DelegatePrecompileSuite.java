// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class DelegatePrecompileSuite {
    private static final long GAS_TO_OFFER = 4_000_000L;
    private static final KeyShape SIMPLE_AND_DELEGATE_KEY_SHAPE =
            KeyShape.threshOf(1, KeyShape.SIMPLE, DELEGATE_CONTRACT);
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, DELEGATE_CONTRACT);
    private static final String TOKEN_TREASURY = "treasury";
    private static final String OUTER_CONTRACT = "DelegateContract";
    private static final String NESTED_CONTRACT = "ServiceContract";
    private static final String ACCOUNT = "anybody";
    private static final String RECEIVER = "receiver";
    private static final String DELEGATE_KEY = "Delegate key";
    private static final String SIMPLE_AND_DELEGATE_KEY_NAME = "Simple And Delegate key";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String DELEGATE_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN =
            "delegateBurnCallWithDelegateContractKeyTxn";

    @HapiTest
    final Stream<DynamicTest> delegateCallForTransfer() {
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
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("First!"))),
                cryptoCreate(ACCOUNT).exposingCreatedIdTo(accountID::set),
                cryptoCreate(RECEIVER).exposingCreatedIdTo(receiverID::set),
                uploadInitCode(OUTER_CONTRACT, NESTED_CONTRACT),
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
                                        OUTER_CONTRACT,
                                        asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec)))
                                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                                // tokenAssociate,
                                // since we have CONTRACT_ID key
                                .refusingEthConversion(),
                        tokenAssociate(OUTER_CONTRACT, VANILLA_TOKEN),
                        newKeyNamed(SIMPLE_AND_DELEGATE_KEY_NAME)
                                .shape(SIMPLE_AND_DELEGATE_KEY_SHAPE.signedWith(sigs(ON, OUTER_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(SIMPLE_AND_DELEGATE_KEY_NAME),
                        contractCall(
                                        OUTER_CONTRACT,
                                        "transferDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(accountID.get())),
                                        HapiParserUtil.asHeadlongAddress(asAddress(receiverID.get())),
                                        1L)
                                .payingWith(GENESIS)
                                .via("delegateTransferCallWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        "delegateTransferCallWithDelegateContractKeyTxn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getAccountBalance(ACCOUNT).hasTokenBalance(VANILLA_TOKEN, 0),
                getAccountBalance(RECEIVER).hasTokenBalance(VANILLA_TOKEN, 1));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> delegateCallForBurn() {
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
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("First!"))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("Second!"))),
                uploadInitCode(OUTER_CONTRACT, NESTED_CONTRACT),
                contractCreate(NESTED_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                OUTER_CONTRACT, asHeadlongAddress(getNestedContractAddress(NESTED_CONTRACT, spec))),
                        newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(OUTER_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        OUTER_CONTRACT,
                                        "burnDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ZERO,
                                        new long[] {1L})
                                .payingWith(GENESIS)
                                .via(DELEGATE_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        DELEGATE_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(1)))
                                .newTotalSupply(1)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(VANILLA_TOKEN, 1));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> delegateCallForMint() {
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
                        newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(OUTER_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(SUPPLY_KEY),
                        contractCall(
                                        OUTER_CONTRACT,
                                        "mintDelegateCall",
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenTokenID.get())),
                                        BigInteger.ONE)
                                .payingWith(GENESIS)
                                .via(DELEGATE_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN)
                                .gas(GAS_TO_OFFER))),
                childRecordsCheck(
                        DELEGATE_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.HAPI_MINT)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(51)
                                                .withSerialNumbers()))
                                .tokenTransfers(changingFungibleBalances().including(VANILLA_TOKEN, TOKEN_TREASURY, 1))
                                .newTotalSupply(51)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(VANILLA_TOKEN, 51));
    }
}
