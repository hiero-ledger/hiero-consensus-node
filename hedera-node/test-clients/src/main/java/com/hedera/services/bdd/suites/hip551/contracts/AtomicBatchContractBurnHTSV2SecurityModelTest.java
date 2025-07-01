// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.SomeFungibleTransfers.changingFungibleBalances;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
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
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hedera.services.bdd.suites.contract.precompile.ContractBurnHTSSuite.ALICE;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.AccountInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
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

@HapiTestLifecycle
public class AtomicBatchContractBurnHTSV2SecurityModelTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    private static final long GAS_TO_OFFER = 2_000_000L;
    private static final String TOKEN_TREASURY = "treasury";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final String CONTRACT_KEY = "ContractKey";
    public static final String MINT_CONTRACT = "MintContract";
    private static final String DELEGATE_CONTRACT_KEY_NAME = "contractKey";
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE =
            KeyShape.threshOf(1, KeyShape.SIMPLE, DELEGATE_CONTRACT);
    public static final String TRESHOLD_KEY_CORRECT_CONTRACT_ID =
            "tresholdKeyWithCorrectContractAndIncorrectSignerPublicKey";
    public static final String TRESHOLD_KEY_WITH_SIGNER_KEY =
            "tresholdKeyWithIncorrectContractAndCorrectSignerPublicKey";
    private static final String SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID =
            "signerMintsAndTokenSupplyKeyHasTheSignerPublicKeyAndTheWrongContractId";
    public static final String THRESHOLD_KEY = "Tresh1WithRandomEdKeyAndCorrectContractID";
    private static final String SIGNER = "anybody";
    private static final String SIGNER2 = "anybody2";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS = "signerAndTokenHaveNoUpdatedKeys";
    private static final String SIGNER_BURNS_WITH_CONTRACT_ID =
            "signerBurnsAndTokenSupplyKeyHasTheIntermediaryContractId";
    private static final String SIGNER_BURNS_WITH_TRESHOLD_KEY = "tokenAndSignerHaveThresholdKey";
    private static final String SIGNER_HAS_KEY_WITH_CORRECT_CONTRACT_ID =
            "signerBurnsAndTokenSupplyKeyHasTheSignerPublicKey";
    private static final String SIGNER_AND_PAYER_ARE_DIFFERENT = "signerAndPayerAreDifferentAccounts";
    private static final String TOKEN_HAS_NO_UPDATED_KEY = "tokenHasUpdatedContractKey";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String BURN_TOKEN = "BurnToken";
    private static final String MIXED_BURN_TOKEN = "MixedBurnToken";
    private static final String BURN_TOKEN_WITH_EVENT = "burnTokenWithEvent";
    private static final String FIRST = "First!";
    private static final String SECOND = "Second!";
    private static final String DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "FungibleTokenHasTheContractIdOnDelegateCall";
    private static final String DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "NonFungibleTokenHasTheContractIdOnDelegateCall";
    private static final String DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS =
            "NonFungibleTokenHasTheContractIdOnDelegateCall";
    private static final String DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS =
            "FungibleTokenHasTheContractIdOnDelegateCall";
    private static final String ACCOUNT_NAME = "anybody";
    private static final String ORDINARY_CALLS_CONTRACT = "HTSCalls";
    private static final String ADMIN_KEY = "ADMIN_KEY";
    private static final String SUPPLY_KEY = "SUPPLY_KEY";
    private static final Long INITIAL_SUPPLY = 20L;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled", "true",
                "atomicBatch.maxNumberOfTransactions", "50",
                "contracts.throttle.throttleByGas", "false"));
        testLifecycle.doAdhoc(
                uploadInitCode(MIXED_BURN_TOKEN),
                uploadInitCode(MINT_CONTRACT),
                uploadInitCode(ORDINARY_CALLS_CONTRACT),
                cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnPositiveCase1() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                // Create a key with shape contract and the contractId of MIXED_BURN_TOKEN contract
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MIXED_BURN_TOKEN)),
                atomicBatchDefaultOperator(
                        // Update the token supply key to with the created key
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 1: Signer paying and signing a token burn transaction
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer will have a key with the contractId (key type CONTRACT)
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(amountToBurn), new long[0])
                                .via(SIGNER_BURNS_WITH_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER)),
                // Assert that the token is burdned - total supply should be decreased with the amount that was
                // burned
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY - amountToBurn),

                // Verify that each test case has 1 successful child record
                getTxnRecord(SIGNER_BURNS_WITH_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnPositiveCase2() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                // Create a key with shape contract and the contractId of MIXED_BURN_TOKEN contract
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MIXED_BURN_TOKEN)),
                atomicBatchDefaultOperator(
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 2: the Treasury account is paying and signing a token burn transaction,
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(amountToBurn), new long[0])
                                .via(SIGNER_HAS_KEY_WITH_CORRECT_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(TOKEN_TREASURY)),
                // Assert that the token is burned - total supply should be increased with the amount to burn.
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY - amountToBurn),

                // Verify that each test case has 1 successful child record
                getTxnRecord(SIGNER_HAS_KEY_WITH_CORRECT_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnPositiveCase3() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(SIGNER2),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                // Create a key with shape contract and the contractId of MIXED_BURN_TOKEN contract
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MIXED_BURN_TOKEN)),
                atomicBatchDefaultOperator(
                        // Update the token supply key to with the created key
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 3: one account  paying and another one signing a token burn transaction
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(amountToBurn), new long[0])
                                .via(SIGNER_AND_PAYER_ARE_DIFFERENT)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER2)
                                .signedBy(SIGNER2, SIGNER)),
                // Assert that the token is burned - total supply should be increased with the amount to burn.
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY - amountToBurn),
                // Verify that each test case has 1 successful child record
                getTxnRecord(SIGNER_AND_PAYER_ARE_DIFFERENT)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnPositiveCase4() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                newKeyNamed(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                atomicBatchDefaultOperator(
                        // Update the token supply key to with the created key
                        tokenUpdate(FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 4: Signer paying and signing a token burn transaction.
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer will have a key with the contractId (key type TRESHOLD_KEY)
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(amountToBurn), new long[0])
                                .via(SIGNER_BURNS_WITH_TRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER)),
                // Assert that the token is burned - total supply should be decreased with the amount that was
                // burned
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY - amountToBurn),
                // Verify that each test case has 1 successful child record
                getTxnRecord(SIGNER_BURNS_WITH_TRESHOLD_KEY)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security005NonFungibleTokenBurnPositiveCase1() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final var serialNumber1 = new long[] {1L};
        return hapiTest(
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Create a key with shape contract and the contractId of burnToken contract
                newKeyNamed(DELEGATE_CONTRACT_KEY_NAME)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                tokenUpdate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(DELEGATE_CONTRACT_KEY_NAME)
                        .signedByPayerAnd(TOKEN_TREASURY),
                // Test Case 1: Treasury account is paying and signing a token burn transaction, where the token
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The signer will have a key with the contractId (key type CONTRACT)
                atomicBatchDefaultOperator(
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(0), serialNumber1)
                                .via(SIGNER_BURNS_WITH_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(TOKEN_TREASURY)),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                getTxnRecord(SIGNER_BURNS_WITH_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security005NonFungibleTokenBurnPositiveCase2() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final var serialNumber1 = new long[] {1L};
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Create a key with shape contract and the contractId of burnToken contract
                newKeyNamed(DELEGATE_CONTRACT_KEY_NAME)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                tokenUpdate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(DELEGATE_CONTRACT_KEY_NAME)
                        .signedByPayerAnd(TOKEN_TREASURY),
                // Test Case 2: Signer account is paying and signing a token burn transaction, where the token
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The signer will have a key with the contractId (key type CONTRACT)
                atomicBatchDefaultOperator(
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(0), serialNumber1)
                                .via(SIGNER_HAS_KEY_WITH_CORRECT_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER)),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                getTxnRecord(SIGNER_HAS_KEY_WITH_CORRECT_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security005NonFungibleTokenBurnPositiveCase3() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final var serialNumber1 = new long[] {1L};
        return hapiTest(
                cryptoCreate(SIGNER2),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Create a key with shape contract and the contractId of burnToken contract
                newKeyNamed(DELEGATE_CONTRACT_KEY_NAME)
                        .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                tokenUpdate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(DELEGATE_CONTRACT_KEY_NAME)
                        .signedByPayerAnd(TOKEN_TREASURY),
                // Test Case 3: one account  paying and another one signing a token burn transaction,
                // SIGNER → call → CONTRACT → call →PRECOMPILE
                atomicBatchDefaultOperator(
                        contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(0), serialNumber1)
                                .via(SIGNER_AND_PAYER_ARE_DIFFERENT)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER2)
                                .signedBy(SIGNER2, SIGNER)),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                getTxnRecord(SIGNER_AND_PAYER_ARE_DIFFERENT)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnNegativeCase1() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                // Test Case 1: Signer paying and signing a token burn transaction,
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The signer and the token don't have updated keys
                atomicBatchDefaultOperator(contractCall(
                                        MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(amountToBurn), new long[0])
                                .via(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // verify that the total supply of the tokens is not affected
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY),
                getTxnRecord(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                        .andAllChildRecords()
                        .hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnNegativeCase2() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MINT_CONTRACT, fungibleAddress.get())),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),

                // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of MINT_CONTRACT
                // contract. MINT_CONTRACT is used only as a "wrong" contract id
                newKeyNamed(TRESHOLD_KEY_WITH_SIGNER_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_CONTRACT))),
                // Update the signer of the transaction to have the threshold key with the wrong contract id
                cryptoUpdate(SIGNER).key(TRESHOLD_KEY_WITH_SIGNER_KEY),

                // Test Case 2: Signer paying and signing a token burn transaction, when the token
                // is expected to  be burned by the token treasury
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The signer and the token have a threshold key with the signer's public key
                // and the wrong contract id (MINT_CONTRACT)
                atomicBatchDefaultOperator(
                                // Update the token's supply to have the threshold key with the wrong contract id
                                tokenUpdate(FUNGIBLE_TOKEN)
                                        .supplyKey(TRESHOLD_KEY_WITH_SIGNER_KEY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                contractCall(
                                                MIXED_BURN_TOKEN,
                                                "burnToken",
                                                BigInteger.valueOf(amountToBurn),
                                                new long[0])
                                        .via(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                                        .gas(GAS_TO_OFFER)
                                        .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY),
                getTxnRecord(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004FungibleTokenBurnNegativeCase3() {
        final var amountToBurn = 5L;
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),

                // Create a key with thresh 1/2 with sigs: new ed25519 key, contractId of MIXED_BURN_TOKEN
                // contract
                // Here the key has the contract`id of the correct contract
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                // Update the Signer with the correct threshold key
                cryptoUpdate(SIGNER).key(THRESHOLD_KEY),
                // Test Case 3: Signer paying and signing a token burn transaction, when the token
                // is expected to  be burned by the token treasury account
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The token has no updated supply key. The signer has the correct threshold key
                atomicBatchDefaultOperator(
                                // Set the token's supply key to the initial one
                                tokenUpdate(FUNGIBLE_TOKEN)
                                        .supplyKey(TOKEN_TREASURY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                contractCall(
                                                MIXED_BURN_TOKEN,
                                                "burnToken",
                                                BigInteger.valueOf(amountToBurn),
                                                new long[0])
                                        .via(TOKEN_HAS_NO_UPDATED_KEY)
                                        .gas(GAS_TO_OFFER)
                                        .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY),
                getTxnRecord(TOKEN_HAS_NO_UPDATED_KEY)
                        .hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004NonFungibleTokenBurnNegativeCase1() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final var serialNumber1 = new long[] {1L};
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Test Case 1: Signer paying and signing a token burn transaction,
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The signer and the token don't have updated keys
                atomicBatchDefaultOperator(
                                contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(0), serialNumber1)
                                        .via(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                                        .gas(GAS_TO_OFFER)
                                        .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                getTxnRecord(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                        .andAllChildRecords()
                        .hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004NonFungibleTokenBurnNegativeCase2() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final var serialNumber1 = new long[] {1L};
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MINT_CONTRACT, nonFungibleAddress.get())),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Create a key with thresh 1/2 with sigs:  new ed25519 key, contract id of MINT_CONTRACT
                // contract. MINT_CONTRACT is only used as a "wrong" contractId
                // Here the key has the contract`id of the wrong contract
                newKeyNamed(TRESHOLD_KEY_WITH_SIGNER_KEY)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_CONTRACT))),
                // Update the signer of the transaction to have the threshold key with the wrong contract id
                cryptoUpdate(SIGNER).key(TRESHOLD_KEY_WITH_SIGNER_KEY),
                // Test Case 2: Signer paying and signing a token burn transaction, when the token
                // is expected to  be burned by the token treasury account
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The signer and the token have a threshold key with the signer's public key
                // and the wrong contract id
                atomicBatchDefaultOperator(
                                // Update the token's supply to have the threshold key with the wrong contract id
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .supplyKey(TRESHOLD_KEY_WITH_SIGNER_KEY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(0), serialNumber1)
                                        .via(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                                        .gas(GAS_TO_OFFER)
                                        .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                getTxnRecord(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security004NonFungibleTokenBurnNegativeCase3() {
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        final var serialNumber1 = new long[] {1L};
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Create a key with thresh 1/2 with sigs: new ed25519 key, contractId of MIXED_BURN_TOKEN
                // contract
                // Here the key has the contract`id of the correct contract
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                // Update the Signer with the correct threshold key
                cryptoUpdate(SIGNER).key(THRESHOLD_KEY),
                // Test Case 3: Signer paying and signing a token burn transaction, when the token
                // is expected to  be burned by the token treasury account
                // SIGNER → call → CONTRACT → call → PRECOMPILE
                // The token has no updated supply key. The signer has the correct threshold key
                atomicBatchDefaultOperator(
                                // Set the token's supply key to the initial one
                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .supplyKey(TOKEN_TREASURY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.valueOf(0), serialNumber1)
                                        .via(TOKEN_HAS_NO_UPDATED_KEY)
                                        .gas(GAS_TO_OFFER)
                                        .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                getTxnRecord(TOKEN_HAS_NO_UPDATED_KEY)
                        .andAllChildRecords()
                        .hasPriority(recordWith().status(CONTRACT_REVERT_EXECUTED))
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security039NftWithContractIdKeyCanNotBurnFromDelegateCallCase1() {
        final var serialNumber1 = new long[] {1L};
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MIXED_BURN_TOKEN)),
                tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                // Test Case 1: Treasury account paying and signing a NON FUNGIBLE token burn transaction
                // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                // The token has updated key
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MIXED_BURN_TOKEN,
                                        "burnTokenDelegateCall",
                                        BigInteger.valueOf(0),
                                        nonFungibleAddress.get(),
                                        serialNumber1)
                                .via(DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(TOKEN_TREASURY))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                // Assert that the token is NOT burned
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                // Assert the token is NOT burned from the token treasury account
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                emptyChildRecordsCheck(
                        DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security039NftWithContractIdKeyCanNotBurnFromDelegateCallCase2() {
        final var serialNumber1 = new long[] {1L};
        final AtomicReference<Address> nonFungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nonFungibleAddress),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8("1"))),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, nonFungibleAddress.get())),
                // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of
                // BURN_TOKEN_VIA_DELEGATE_CALL contract
                newKeyNamed(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                        .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                // Update the token's supply to have the threshold key wit the wrong contract id
                tokenUpdate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                        .signedByPayerAnd(TOKEN_TREASURY),
                // Update the signer of the transaction to have the threshold key with the wrong contract id
                cryptoUpdate(SIGNER).key(TRESHOLD_KEY_CORRECT_CONTRACT_ID),
                // Test Case 2: A Signer paying and signing a NON FUNGIBLE token burn transaction,
                // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                // The token and the signer have updated keys
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MIXED_BURN_TOKEN,
                                        "burnTokenDelegateCall",
                                        BigInteger.valueOf(0),
                                        nonFungibleAddress.get(),
                                        serialNumber1)
                                .via(DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                // Assert that the token is NOT burned
                getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(1L),
                // Assert the token is NOT burned from the token treasury account
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                emptyChildRecordsCheck(
                        DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security039FungibleWithContractIdKeyCanNotBurnFromDelegateCallCase1() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MIXED_BURN_TOKEN)),
                tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                // Test Case 1: Treasury account paying and signing a FUNGIBLE token burn transaction
                // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                // The token has updated key
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MIXED_BURN_TOKEN,
                                        "burnTokenDelegateCall",
                                        BigInteger.valueOf(1L),
                                        fungibleAddress.get(),
                                        new long[0])
                                .via(DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(TOKEN_TREASURY))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                // Assert that the token is NOT burned
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY),
                // Assert the token is NOT burned from the token treasury account
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, INITIAL_SUPPLY),
                emptyChildRecordsCheck(DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security039FungibleWithContractIdKeyCanNotBurnFromDelegateCallCase2() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                sourcing(() -> contractCreate(MIXED_BURN_TOKEN, fungibleAddress.get())),
                newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MIXED_BURN_TOKEN)),
                tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                // Test Case 2: A Signer paying and signing a FUNGIBLE token burn transaction
                // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                // The token and the signer have updated keys
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        MIXED_BURN_TOKEN,
                                        "burnTokenDelegateCall",
                                        BigInteger.valueOf(1L),
                                        fungibleAddress.get(),
                                        new long[0])
                                .via(DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                // Assert that the token is NOT burned
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(INITIAL_SUPPLY),
                // Assert the token is NOT burned from the token treasury account
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, INITIAL_SUPPLY),
                emptyChildRecordsCheck(
                        DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> V2SecurityBurnTokenWithFullPrefixAndPartialPrefixKeys() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> fungibleAddress2 = new AtomicReference<>();
        final var firstBurnTxn = "firstBurnTxn";
        final var secondBurnTxn = "secondBurnTxn";
        final var amount = 19L;

        return hapiTest(
                newKeyNamed(SIGNER),
                contractCreate(ORDINARY_CALLS_CONTRACT),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, ORDINARY_CALLS_CONTRACT))),
                cryptoCreate(ACCOUNT_NAME).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN_2, fungibleAddress2),
                tokenUpdate(FUNGIBLE_TOKEN).supplyKey(THRESHOLD_KEY).signedByPayerAnd(TOKEN_TREASURY),
                sourcing(() -> atomicBatchDefaultOperator(
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "burnTokenCall",
                                        fungibleAddress2.get(),
                                        BigInteger.ONE,
                                        new long[0])
                                .via(firstBurnTxn)
                                .payingWith(ACCOUNT_NAME)
                                .signedBy(SIGNER),
                        contractCall(
                                        ORDINARY_CALLS_CONTRACT,
                                        "burnTokenCall",
                                        fungibleAddress.get(),
                                        BigInteger.ONE,
                                        new long[0])
                                .via(secondBurnTxn)
                                .payingWith(ACCOUNT_NAME)
                                .alsoSigningWithFullPrefix(SIGNER, THRESHOLD_KEY, ACCOUNT_NAME))),
                childRecordsCheck(
                        firstBurnTxn,
                        SUCCESS,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                childRecordsCheck(
                        secondBurnTxn,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(amount)))
                                .newTotalSupply(amount)),
                getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(amount),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, amount));
    }

    @HapiTest
    final Stream<DynamicTest> V2SecurityHscsPreC020RollbackBurnThatFailsAfterAPrecompileTransfer() {
        final var bob = "bob";
        final var feeCollector = "feeCollector";
        final var tokenWithHbarFee = "tokenWithHbarFee";
        final var theContract = "TransferAndBurn";
        final var SUPPLY_KEY = "SUPPLY_KEY";
        final var ADMIN_KEY = "ADMIN_KEY";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ADMIN_KEY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(bob).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(feeCollector).balance(0L),
                tokenCreate(tokenWithHbarFee)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .initialSupply(0L)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set)
                        .withCustom(fixedHbarFee(300 * ONE_HBAR, feeCollector)),
                mintToken(tokenWithHbarFee, List.of(copyFromUtf8(FIRST))),
                mintToken(tokenWithHbarFee, List.of(copyFromUtf8(SECOND))),
                uploadInitCode(theContract),
                sourcing(() -> contractCreate(theContract, tokenAddress.get())
                        .payingWith(bob)
                        .gas(GAS_TO_OFFER)),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, theContract))),
                tokenUpdate(tokenWithHbarFee).supplyKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                tokenAssociate(ALICE, tokenWithHbarFee),
                tokenAssociate(bob, tokenWithHbarFee),
                tokenAssociate(theContract, tokenWithHbarFee),
                cryptoTransfer(movingUnique(tokenWithHbarFee, 2L).between(TOKEN_TREASURY, ALICE))
                        .payingWith(GENESIS),
                getAccountInfo(feeCollector)
                        .has(AccountInfoAsserts.accountWith().balance(0L)),
                withOpContext((spec, opLog) -> {
                    final var serialNumbers = new long[] {1L};
                    allRunFor(
                            spec,
                            atomicBatchDefaultOperator(contractCall(
                                                    theContract,
                                                    "transferBurn",
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getAccountID(ALICE))),
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getAccountID(bob))),
                                                    BigInteger.ZERO,
                                                    2L,
                                                    serialNumbers)
                                            .alsoSigningWithFullPrefix(ALICE, THRESHOLD_KEY)
                                            .gas(GAS_TO_OFFER)
                                            .via("contractCallTxn"))
                                    .hasKnownStatus(INNER_TRANSACTION_FAILED));
                }),
                childRecordsCheck(
                        "contractCallTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(REVERTED_SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(1))),
                        recordWith()
                                .status(SPENDER_DOES_NOT_HAVE_ALLOWANCE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SPENDER_DOES_NOT_HAVE_ALLOWANCE)))),
                getAccountBalance(bob).hasTokenBalance(tokenWithHbarFee, 0),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(tokenWithHbarFee, 1),
                getAccountBalance(ALICE).hasTokenBalance(tokenWithHbarFee, 1));
    }

    @HapiTest
    final Stream<DynamicTest> V2SecurityHscsPrec004TokenBurnOfFungibleTokenUnits() {
        final var gasUsed = 15284L;
        final var CREATION_TX = "CREATION_TX";
        final var MULTI_KEY = "MULTI_KEY";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(MULTI_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                uploadInitCode(MIXED_BURN_TOKEN),
                sourcing(() -> atomicBatchDefaultOperator(contractCreate(MIXED_BURN_TOKEN, tokenAddress.get())
                        .payingWith(ALICE)
                        .via(CREATION_TX)
                        .gas(GAS_TO_OFFER))),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MIXED_BURN_TOKEN))),
                tokenUpdate(FUNGIBLE_TOKEN).supplyKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                atomicBatchDefaultOperator(
                        contractCall(MIXED_BURN_TOKEN, BURN_TOKEN_WITH_EVENT, BigInteger.ZERO, new long[0])
                                .payingWith(ALICE)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("burnZero")),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 50),
                atomicBatchDefaultOperator(
                        contractCall(MIXED_BURN_TOKEN, BURN_TOKEN_WITH_EVENT, BigInteger.ONE, new long[0])
                                .payingWith(ALICE)
                                .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .via("burn")),
                getTxnRecord("burn")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(logWith()
                                                .noData()
                                                .withTopicsInOrder(List.of(parsedToByteString(0, 0, 49))))))),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 49),
                childRecordsCheck(
                        "burn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(49))
                                        .gasUsed(gasUsed))
                                .newTotalSupply(49)
                                .tokenTransfers(
                                        changingFungibleBalances().including(FUNGIBLE_TOKEN, TOKEN_TREASURY, -1))
                                .newTotalSupply(49)),
                contractCall(MIXED_BURN_TOKEN, "burnToken", BigInteger.ONE, new long[0])
                        .via("burn with contract key")
                        .gas(GAS_TO_OFFER),
                childRecordsCheck(
                        "burn with contract key",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(48)))
                                .newTotalSupply(48)
                                .tokenTransfers(
                                        changingFungibleBalances().including(FUNGIBLE_TOKEN, TOKEN_TREASURY, -1))),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 48));
    }

    @HapiTest
    final Stream<DynamicTest> V2SecurityHscsPrec011BurnAfterNestedMint() {
        final var innerContract = "MintToken";
        final var outerContract = "NestedBurn";
        final var revisedKey = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT, DELEGATE_CONTRACT);
        final var SUPPLY_KEY = "SUPPLY_KEY";
        final var CREATION_TX = "CREATION_TX";
        final var BURN_AFTER_NESTED_MINT_TX = "burnAfterNestedMint";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(50L)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                uploadInitCode(innerContract, outerContract),
                contractCreate(innerContract).gas(GAS_TO_OFFER),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(outerContract, asHeadlongAddress(getNestedContractAddress(innerContract, spec)))
                                .payingWith(ALICE)
                                .via(CREATION_TX)
                                .gas(GAS_TO_OFFER))),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, outerContract))),
                tokenUpdate(FUNGIBLE_TOKEN).supplyKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                getTxnRecord(CREATION_TX).logged(),
                newKeyNamed(CONTRACT_KEY).shape(revisedKey.signedWith(sigs(ON, innerContract, outerContract))),
                sourcing(() -> atomicBatchDefaultOperator(
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(ADMIN_KEY),
                        contractCall(
                                        outerContract,
                                        BURN_AFTER_NESTED_MINT_TX,
                                        BigInteger.ONE,
                                        tokenAddress.get(),
                                        new long[0])
                                .payingWith(ALICE)
                                .alsoSigningWithFullPrefix(CONTRACT_KEY)
                                .hasKnownStatus(SUCCESS)
                                .via(BURN_AFTER_NESTED_MINT_TX))),
                childRecordsCheck(
                        BURN_AFTER_NESTED_MINT_TX,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_MINT)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(51)
                                                .withSerialNumbers()))
                                .tokenTransfers(changingFungibleBalances().including(FUNGIBLE_TOKEN, TOKEN_TREASURY, 1))
                                .newTotalSupply(51),
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(50)))
                                .tokenTransfers(
                                        changingFungibleBalances().including(FUNGIBLE_TOKEN, TOKEN_TREASURY, -1))
                                .newTotalSupply(50)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 50));
    }

    @HapiTest
    final Stream<DynamicTest> V2SecurityHscsPrec005TokenBurnOfNft() {
        final var gasUsed = 15284L;
        final var CREATION_TX = "CREATION_TX";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .adminKey(ADMIN_KEY)
                        .treasury(TOKEN_TREASURY)
                        .exposingAddressTo(tokenAddress::set),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8(FIRST))),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(copyFromUtf8(SECOND))),
                uploadInitCode(BURN_TOKEN),
                sourcing(() -> contractCreate(BURN_TOKEN, tokenAddress.get())
                        .payingWith(ALICE)
                        .via(CREATION_TX)
                        .gas(GAS_TO_OFFER)),
                newKeyNamed(THRESHOLD_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, BURN_TOKEN))),
                tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(THRESHOLD_KEY).signedByPayerAnd(ADMIN_KEY),
                getTxnRecord(CREATION_TX).logged(),
                atomicBatchDefaultOperator(contractCall(BURN_TOKEN, "burnToken", BigInteger.ZERO, new long[] {1L})
                        .payingWith(ALICE)
                        .alsoSigningWithFullPrefix(THRESHOLD_KEY)
                        .gas(GAS_TO_OFFER)
                        .via("burn")),
                childRecordsCheck(
                        "burn",
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(ParsingConstants.FunctionType.HAPI_BURN)
                                                .withStatus(SUCCESS)
                                                .withTotalSupply(1))
                                        .gasUsed(gasUsed))
                                .newTotalSupply(1)),
                getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1));
    }

    /* --------------- Helper methods --------------- */

    private HapiAtomicBatch atomicBatchDefaultOperator(HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }

    private HapiTokenCreate createFungibleAndExposeAdr(String tokenName, AtomicReference<Address> addressRef) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(TOKEN_TREASURY)
                .supplyKey(TOKEN_TREASURY)
                .adminKey(TOKEN_TREASURY)
                .initialSupply(INITIAL_SUPPLY)
                .exposingAddressTo(addressRef::set);
    }

    private HapiTokenCreate createNftAndExposeAdr(String tokenName, AtomicReference<Address> addressRef) {
        return tokenCreate(tokenName)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0)
                .treasury(TOKEN_TREASURY)
                .supplyKey(TOKEN_TREASURY)
                .adminKey(TOKEN_TREASURY)
                .exposingAddressTo(addressRef::set);
    }
}
