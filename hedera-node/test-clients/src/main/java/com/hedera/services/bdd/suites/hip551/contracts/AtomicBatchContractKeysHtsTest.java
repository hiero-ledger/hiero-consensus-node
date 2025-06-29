// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.google.protobuf.ByteString.copyFromUtf8;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_STILL_OWNS_NFTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.NonFungibleTransfers;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

/**
 * This class tests the behavior of atomic batch operations
 * involving contract keys and token operations.
 * It includes various scenarios for minting, burning, associating,
 * and dissociating tokens using contract and delegate keys.
 */
@HapiTestLifecycle
public class AtomicBatchContractKeysHtsTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";
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

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(
                cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                uploadInitCode(
                        BURN_TOKEN,
                        OUTER_CONTRACT,
                        NESTED_CONTRACT,
                        NESTED_ASSOCIATE_DISSOCIATE,
                        ASSOCIATE_DISSOCIATE_CONTRACT,
                        STATIC_CONTRACT,
                        ORDINARY_CALLS_CONTRACT),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(KYC_KEY),
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(UNIVERSAL_KEY),
                cryptoCreate(TOKEN_TREASURY));
    }

    @HapiTest
    public final Stream<DynamicTest> burnWithKeyAsPartOf1OfXThreshold() {
        final var delegateContractKeyShape = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var contractKeyShape = KeyShape.threshOf(1, SIMPLE, KeyShape.CONTRACT);
        final var tokenAddress = new AtomicReference<Address>();
        return hapiTest(
                tokenCreate(TOKEN_USAGE)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                sourcing(() -> contractCreate(BURN_TOKEN, tokenAddress.get())),
                newKeyNamed(DELEGATE_KEY).shape(delegateContractKeyShape.signedWith(sigs(ON, BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        tokenUpdate(TOKEN_USAGE).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                                .via("burn with delegate contract key")
                                .gas(GAS_TO_OFFER)),
                childRecordsCheck(
                        "burn with delegate contract key",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN_USAGE, 49),
                newKeyNamed(CONTRACT_KEY).shape(contractKeyShape.signedWith(sigs(ON, BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        tokenUpdate(TOKEN_USAGE).supplyKey(CONTRACT_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                                .via(BURN_WITH_CONTRACT_KEY)
                                .gas(GAS_TO_OFFER)),
                childRecordsCheck(
                        BURN_WITH_CONTRACT_KEY,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(48)))
                                .tokenTransfers(
                                        changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))));
    }

    @HapiTest
    public final Stream<DynamicTest> delegateCallForBurnWithContractKey() {
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(SECOND_STR_FOR_MINT))),
                contractCreate(NESTED_CONTRACT),
                nestedContractCreate(OUTER_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, OUTER_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(SUPPLY_KEY),
                                contractCall(
                                                OUTER_CONTRACT,
                                                "burnDelegateCall",
                                                vanillaTokenTokenAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {1L})
                                        .via(DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                childRecordsCheck(
                        DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(VANILLA_TOKEN, 2));
    }

    @HapiTest
    public final Stream<DynamicTest> delegateCallForMintWithContractKey() {
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(50L)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(NESTED_CONTRACT),
                nestedContractCreate(OUTER_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, OUTER_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(SUPPLY_KEY),
                                contractCall(
                                                OUTER_CONTRACT,
                                                "mintDelegateCall",
                                                vanillaTokenTokenAddress.get(),
                                                BigInteger.ONE)
                                        .via(DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                childRecordsCheck(
                        DELEGATE_BURN_CALL_WITH_CONTRACT_KEY_TXN,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_MINT)
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                                .withSerialNumbers()))),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(VANILLA_TOKEN, 50));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForDissociatePrecompileFails() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                nestedContractCreate(NESTED_ASSOCIATE_DISSOCIATE, ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        NESTED_ASSOCIATE_DISSOCIATE,
                                        "dissociateStaticCall",
                                        accountAddress.get(),
                                        vanillaTokenTokenAddress.get())
                                .via("staticDissociateCallTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck("staticDissociateCallTxn", CONTRACT_REVERT_EXECUTED),
                getAccountInfo(ACCOUNT).hasToken(relationshipWith(VANILLA_TOKEN)));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForTransferWithContractKey() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(RECEIVER).exposingEvmAddressTo(receiverAddress::set),
                contractCreate(NESTED_CONTRACT),
                tokenAssociate(NESTED_CONTRACT, VANILLA_TOKEN),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT)),
                nestedContractCreate(STATIC_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, STATIC_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenAssociate(STATIC_CONTRACT, VANILLA_TOKEN),
                                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                                contractCall(
                                                STATIC_CONTRACT,
                                                "transferStaticCall",
                                                vanillaTokenTokenAddress.get(),
                                                accountAddress.get(),
                                                receiverAddress.get(),
                                                1L)
                                        .via("staticTransferCallWithContractKeyTxn")
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck("staticTransferCallWithContractKeyTxn", CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForBurnWithContractKey() {
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(SECOND_STR_FOR_MINT))),
                contractCreate(NESTED_CONTRACT),
                nestedContractCreate(STATIC_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, STATIC_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(SUPPLY_KEY),
                                contractCall(
                                                STATIC_CONTRACT,
                                                "burnStaticCall",
                                                vanillaTokenTokenAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {1L})
                                        .via(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForMintWithContractKey() {
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(50L)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(NESTED_CONTRACT),
                nestedContractCreate(STATIC_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, STATIC_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(SUPPLY_KEY),
                                contractCall(
                                                STATIC_CONTRACT,
                                                "mintStaticCall",
                                                vanillaTokenTokenAddress.get(),
                                                BigInteger.ONE)
                                        .via(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForTransferWithDelegateContractKey() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(RECEIVER).exposingEvmAddressTo(receiverAddress::set),
                contractCreate(NESTED_CONTRACT).refusingEthConversion(),
                nestedContractCreate(STATIC_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, STATIC_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenAssociate(NESTED_CONTRACT, VANILLA_TOKEN),
                                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT)),
                                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                                tokenAssociate(STATIC_CONTRACT, VANILLA_TOKEN),
                                contractCall(
                                                STATIC_CONTRACT,
                                                "transferStaticCall",
                                                vanillaTokenTokenAddress.get(),
                                                accountAddress.get(),
                                                receiverAddress.get(),
                                                1L)
                                        .via("staticTransferCallWithDelegateContractKeyTxn")
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck("staticTransferCallWithDelegateContractKeyTxn", CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForBurnWithDelegateContractKey() {
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0L)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(FIRST_STRING_FOR_MINT))),
                mintToken(VANILLA_TOKEN, List.of(copyFromUtf8(SECOND_STR_FOR_MINT))),
                contractCreate(NESTED_CONTRACT),
                nestedContractCreate(STATIC_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, STATIC_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN)
                                        .supplyKey(DELEGATE_KEY)
                                        .signedByPayerAnd(SUPPLY_KEY),
                                contractCall(
                                                STATIC_CONTRACT,
                                                "burnStaticCall",
                                                vanillaTokenTokenAddress.get(),
                                                BigInteger.ZERO,
                                                new long[] {1L})
                                        .via(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForMintWithDelegateContractKey() {
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(50L)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(NESTED_CONTRACT),
                nestedContractCreate(STATIC_CONTRACT, NESTED_CONTRACT),
                newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, STATIC_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                                tokenUpdate(VANILLA_TOKEN)
                                        .supplyKey(DELEGATE_KEY)
                                        .signedByPayerAnd(SUPPLY_KEY),
                                contractCall(
                                                STATIC_CONTRACT,
                                                "mintStaticCall",
                                                vanillaTokenTokenAddress.get(),
                                                BigInteger.ONE)
                                        .via(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck(STATIC_BURN_CALL_WITH_DELEGATE_CONTRACT_KEY_TXN, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    public final Stream<DynamicTest> staticCallForAssociatePrecompileFails() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                nestedContractCreate(NESTED_ASSOCIATE_DISSOCIATE, ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        NESTED_ASSOCIATE_DISSOCIATE,
                                        "associateStaticCall",
                                        accountAddress.get(),
                                        vanillaTokenTokenAddress.get())
                                .payingWith(ACCOUNT)
                                .via("staticAssociateCallTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                emptyChildRecordsCheck("staticAssociateCallTxn", CONTRACT_REVERT_EXECUTED),
                getAccountInfo(ACCOUNT).hasNoTokenRelationship(VANILLA_TOKEN));
    }

    @HapiTest
    public final Stream<DynamicTest> callForMintWithContractKey() {
        final var firstMintTxn = "firstMintTxn";
        final var amount = 10L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT_NAME).balance(10 * ONE_HUNDRED_HBARS),
                tokenCreate(TYPE_OF_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingAddressTo(fungibleAddress::set),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenUpdate(TYPE_OF_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "mintTokenCall",
                                        fungibleAddress.get(),
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
                                                .forFunction(ParsingConstants.FunctionType.HAPI_MINT)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(10)
                                                .withSerialNumbers()))
                                .tokenTransfers(changingFungibleBalances().including(TYPE_OF_TOKEN, TOKEN_TREASURY, 10))
                                .newTotalSupply(10)),
                getTokenInfo(TYPE_OF_TOKEN).hasTotalSupply(amount),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TYPE_OF_TOKEN, amount));
    }

    @HapiTest
    public final Stream<DynamicTest> callForMintWithDelegateContractKey() {
        final var firstMintTxn = "firstMintTxn";
        final var amount = 10L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT_NAME).balance(10 * ONE_HUNDRED_HBARS),
                tokenCreate(TYPE_OF_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingAddressTo(fungibleAddress::set),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenUpdate(TYPE_OF_TOKEN).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "mintTokenCall",
                                        fungibleAddress.get(),
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
                                                .forFunction(ParsingConstants.FunctionType.HAPI_MINT)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(10)
                                                .withSerialNumbers()))
                                .tokenTransfers(changingFungibleBalances().including(TYPE_OF_TOKEN, TOKEN_TREASURY, 10))
                                .newTotalSupply(10)),
                getTokenInfo(TYPE_OF_TOKEN).hasTotalSupply(amount),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TYPE_OF_TOKEN, amount));
    }

    @HapiTest
    public final Stream<DynamicTest> callForTransferWithContractKey() {
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(RECEIVER).exposingEvmAddressTo(receiverAddress::set),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(UNIVERSAL_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(nftAddress::set),
                mintToken(NFT, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenAssociate(ACCOUNT, NFT),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        tokenAssociate(ORDINARY_CALLS_CONTRACT, List.of(NFT)),
                        tokenAssociate(RECEIVER, List.of(NFT)),
                        cryptoTransfer(movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "transferNFTCall",
                                        nftAddress.get(),
                                        accountAddress.get(),
                                        receiverAddress.get(),
                                        1L)
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
    public final Stream<DynamicTest> callForTransferWithDelegateContractKey() {
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> receiverAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(10 * ONE_HUNDRED_HBARS).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(RECEIVER).exposingEvmAddressTo(receiverAddress::set),
                tokenCreate(NFT)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(UNIVERSAL_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(nftAddress::set),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenAssociate(ACCOUNT, NFT),
                        mintToken(NFT, List.of(metadata("firstMemo"), metadata("secondMemo"))),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        tokenAssociate(ORDINARY_CALLS_CONTRACT, List.of(NFT)),
                        tokenAssociate(RECEIVER, List.of(NFT)),
                        cryptoTransfer(movingUnique(NFT, 1).between(TOKEN_TREASURY, ACCOUNT)),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "transferNFTCall",
                                        nftAddress.get(),
                                        accountAddress.get(),
                                        receiverAddress.get(),
                                        1L)
                                .fee(2 * ONE_HBAR)
                                .hasKnownStatus(SUCCESS)
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
    public final Stream<DynamicTest> callForAssociateWithDelegateContractKey() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via(VANILLA_TOKEN_ASSOCIATE_TXN)
                                .gas(GAS_TO_OFFER))),
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
    public final Stream<DynamicTest> callForAssociateWithContractKey() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via(VANILLA_TOKEN_ASSOCIATE_TXN)
                                .gas(GAS_TO_OFFER))),
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
    public final Stream<DynamicTest> callForDissociateWithDelegateContractKey() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        final var totalSupply = 1_000;
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(totalSupply)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                sourcing(() -> atomicBatchDefaultOperator(
                                // fails with TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES
                                contractCall(
                                                ASSOCIATE_DISSOCIATE_CONTRACT,
                                                TOKEN_DISSOCIATE,
                                                accountAddress.get(),
                                                vanillaTokenAddress.get())
                                        .via(NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via(TOKEN_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_HAPPY_TXN)
                                .gas(GAS_TO_OFFER))),
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
    public final Stream<DynamicTest> callForDissociateWithContractKey() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        final var totalSupply = 1_000;

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(totalSupply)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                cryptoUpdate(TOKEN_TREASURY).key(CONTRACT_KEY),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("nonZeroTokenBalanceDissociateWithContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("tokenDissociateWithContractKeyHappyTxn")
                                .gas(GAS_TO_OFFER))),
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
    public final Stream<DynamicTest> callForBurnWithDelegateContractKey() {
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(TOKEN_USAGE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                sourcing(() -> contractCreate(BURN_TOKEN, tokenAddress.get())),
                newKeyNamed(DELEGATE_KEY).shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        tokenUpdate(TOKEN_USAGE).supplyKey(DELEGATE_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                                .via(BURN_WITH_CONTRACT_KEY)
                                .gas(GAS_TO_OFFER)),
                childRecordsCheck(
                        BURN_WITH_CONTRACT_KEY,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN_USAGE, 49));
    }

    @HapiTest
    public final Stream<DynamicTest> delegateCallForAssociatePrecompileSignedWithDelegateContractKeyWorks() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                nestedContractCreate(NESTED_ASSOCIATE_DISSOCIATE, ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_DISSOCIATE))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        NESTED_ASSOCIATE_DISSOCIATE,
                                        "associateDelegateCall",
                                        accountAddress.get(),
                                        vanillaTokenTokenAddress.get())
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
    public final Stream<DynamicTest> delegateCallForDissociatePrecompileSignedWithDelegateContractKeyWorks() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                nestedContractCreate(NESTED_ASSOCIATE_DISSOCIATE, ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_DISSOCIATE))),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        NESTED_ASSOCIATE_DISSOCIATE,
                                        "dissociateDelegateCall",
                                        accountAddress.get(),
                                        vanillaTokenTokenAddress.get())
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
    public final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForNonFungibleWithKyc() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> kycTokenAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(KYC_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(GENESIS)
                        .kycKey(KYC_KEY)
                        .exposingAddressTo(kycTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycNFTAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycNFTAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycNFTSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForFungibleVanilla() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> treasuryAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(TOKEN_TREASURY).exposingEvmAddressTo(treasuryAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                cryptoUpdate(TOKEN_TREASURY).key(DELEGATE_KEY),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        treasuryAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("tokenDissociateFromTreasuryFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("tokenDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(moving(1, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via(NON_ZERO_TOKEN_BALANCE_DISSOCIATE_WITH_DELEGATE_CONTRACT_KEY_FAILED_TXN)
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoTransfer(moving(1, VANILLA_TOKEN).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
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
    public final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForFungibleFrozen() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> frozenTokenAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .freezeDefault(true)
                        .freezeKey(FREEZE_KEY)
                        .exposingAddressTo(frozenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                tokenAssociate(ACCOUNT, FROZEN_TOKEN),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenTokenAssociateWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenUnfreeze(FROZEN_TOKEN, ACCOUNT),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
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
    public final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForFungibleWithKyc() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> kycTokenAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(KYC_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .kycKey(KYC_KEY)
                        .exposingAddressTo(kycTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycTokenDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenAssociate(ACCOUNT, KYC_TOKEN),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
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
    public final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForNonFungibleVanilla() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> treasuryAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                cryptoCreate(TOKEN_TREASURY).balance(0L).exposingEvmAddressTo(treasuryAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(MULTI_KEY)
                        .exposingAddressTo(vanillaTokenAddress::set),
                mintToken(VANILLA_TOKEN, List.of(metadata("memo"))),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                cryptoUpdate(TOKEN_TREASURY).key(DELEGATE_KEY),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        treasuryAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("NFTDissociateFromTreasuryFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("NFTDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                cryptoTransfer(movingUnique(VANILLA_TOKEN, 1).between(TOKEN_TREASURY, ACCOUNT)),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("nonZeroNFTBalanceDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoTransfer(movingUnique(VANILLA_TOKEN, 1).between(ACCOUNT, TOKEN_TREASURY)),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
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
    public final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForNonFungibleFrozen() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> frozenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(GENESIS)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .freezeDefault(true)
                        .freezeKey(FREEZE_KEY)
                        .exposingAddressTo(frozenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                tokenAssociate(ACCOUNT, FROZEN_TOKEN),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenNFTAssociateWithDelegateContractKeyTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenUnfreeze(FROZEN_TOKEN, ACCOUNT),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
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
    public final Stream<DynamicTest> dissociatePrecompileWithDelegateContractKeyForNonFungibleWithKyc() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> kycTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(KYC_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(GENESIS)
                        .initialSupply(0)
                        .kycKey(KYC_KEY)
                        .exposingAddressTo(kycTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycNFTDissociateWithDelegateContractKeyFailedTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenAssociate(ACCOUNT, KYC_TOKEN),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_DISSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
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
    public final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForNonFungibleFrozen() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> frozenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(GENESIS)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .exposingAddressTo(frozenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenNFTAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenNFTAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenNFTSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForNonFungibleVanilla() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(GENESIS)
                        .initialSupply(0)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("vanillaNFTAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("vanillaNFTAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("vanillaNFTSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForFungibleWithKyc() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> kycTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(KYC_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .kycKey(KYC_KEY)
                        .exposingAddressTo(kycTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycTokenAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycTokenAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        kycTokenAddress.get())
                                .via("kycTokenSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForFungibleFrozen() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> frozenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(FROZEN_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(TOTAL_SUPPLY)
                        .freezeKey(FREEZE_KEY)
                        .freezeDefault(true)
                        .exposingAddressTo(frozenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenTokenAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenTokenAssociateTxn")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        frozenTokenAddress.get())
                                .via("frozenTokenSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> associatePrecompileWithDelegateContractKeyForFungibleVanilla() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("vanillaTokenAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                newKeyNamed(DELEGATE_KEY)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_DISSOCIATE_CONTRACT))),
                sourcing(() -> atomicBatchDefaultOperator(
                        cryptoUpdate(ACCOUNT).key(DELEGATE_KEY),
                        contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via(VANILLA_TOKEN_ASSOCIATE_TXN)
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(SUCCESS))),
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_DISSOCIATE_CONTRACT,
                                        TOKEN_ASSOCIATE,
                                        accountAddress.get(),
                                        vanillaTokenAddress.get())
                                .via("vanillaTokenSecondAssociateFailsTxn")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> delegateCallForAssociatePrecompileSignedWithContractKeyFails() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                nestedContractCreate(NESTED_ASSOCIATE_DISSOCIATE, ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_DISSOCIATE))),
                sourcing(() -> atomicBatchDefaultOperator(
                                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                                contractCall(
                                                NESTED_ASSOCIATE_DISSOCIATE,
                                                "associateDelegateCall",
                                                accountAddress.get(),
                                                vanillaTokenTokenAddress.get())
                                        .via("delegateAssociateCallWithContractKeyTxn")
                                        .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> delegateCallForDissociatePrecompileSignedWithContractKeyFails() {
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        final AtomicReference<Address> vanillaTokenTokenAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(ACCOUNT).exposingEvmAddressTo(accountAddress::set),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(vanillaTokenTokenAddress::set),
                contractCreate(ASSOCIATE_DISSOCIATE_CONTRACT),
                nestedContractCreate(NESTED_ASSOCIATE_DISSOCIATE, ASSOCIATE_DISSOCIATE_CONTRACT),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_DISSOCIATE))),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                sourcing(() -> atomicBatchDefaultOperator(
                                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                                contractCall(
                                                NESTED_ASSOCIATE_DISSOCIATE,
                                                "dissociateDelegateCall",
                                                accountAddress.get(),
                                                vanillaTokenTokenAddress.get())
                                        .via("delegateDissociateCallWithContractKeyTxn")
                                        .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
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
    public final Stream<DynamicTest> callForBurnWithContractKey() {
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        return hapiTest(
                tokenCreate(TOKEN_USAGE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                withOpContext((spec, opLog) -> allRunFor(spec, contractCreate(BURN_TOKEN, tokenAddress.get()))),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT_KEY_SHAPE.signedWith(sigs(ON, BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        tokenUpdate(TOKEN_USAGE).supplyKey(CONTRACT_KEY).signedByPayerAnd(MULTI_KEY),
                        contractCall(BURN_TOKEN, BURN_TOKEN_METHOD, BigInteger.ONE, new long[0])
                                .via(BURN_WITH_CONTRACT_KEY)
                                .gas(GAS_TO_OFFER)),
                childRecordsCheck(
                        BURN_WITH_CONTRACT_KEY,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49)))
                                .tokenTransfers(changingFungibleBalances().including(TOKEN_USAGE, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(TOKEN_USAGE, 49));
    }

    private HapiAtomicBatch atomicBatchDefaultOperator(final HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }

    private HapiSpecOperation nestedContractCreate(final String contractToCreate, final String parentContract) {
        return withOpContext((spec, opLog) -> allRunFor(
                spec,
                contractCreate(contractToCreate, asHeadlongAddress(getNestedContractAddress(parentContract, spec)))));
    }
}
