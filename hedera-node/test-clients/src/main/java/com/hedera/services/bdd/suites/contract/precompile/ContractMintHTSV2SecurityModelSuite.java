// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.emptyChildRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintTranslator;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
public class ContractMintHTSV2SecurityModelSuite {
    private static final long GAS_TO_OFFER = 2_000_000L;
    private static final String TOKEN_TREASURY = "treasury";
    private static final KeyShape TRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final String CONTRACT_KEY = "ContractKey";
    public static final String MINT_CONTRACT = "MintContract";
    private static final String HTS_CALLS = "HTSCalls";
    public static final String TRESHOLD_KEY_CORRECT_CONTRACT_ID =
            "tresholdKeyWithCorrectContractAndIncorrectSignerPublicKey";
    public static final String TRESHOLD_KEY_WITH_SIGNER_KEY =
            "tresholdKeyWithIncorrectContractAndCorrectSignerPublicKey";
    public static final String THRESHOLD_KEY = "Tresh1WithRandomEdKeyAndCorrectContractID";
    private static final String SIGNER = "anybody";
    private static final String RECEIVER = "anybody";
    private static final String SIGNER2 = "anybody";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS = "signerAndTokenHaveNoUpdatedKeys";
    private static final String DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "FungibleTokenHasTheContractIdOnDelegateCall";
    private static final String STATIC_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "FungibleTokenHasTheContractIdOnStaticCall";
    private static final String STATIC_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "NonFungibleTokenHasTheContractIdOnStaticCall";
    private static final String DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "NonFungibleTokenHasTheContractIdOnDelegateCall";
    private static final String DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS =
            "NonFungibleTokenHasTheContractIdOnDelegateCall";
    private static final String DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS =
            "FungibleTokenHasTheContractIdOnDelegateCall";
    private static final String CALLCODE_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "FungibleTokenHasTheContractIdOnCallcode";
    private static final String CALLCODE_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID =
            "NonFungibleTokenHasTheContractIdOnCallcode";
    private static final String TOKEN_HAS_NO_UPDATED_KEY = "tokenHasUpdatedContractKey";
    private static final String SIGNER_MINTS_WITH_CONTRACT_ID =
            "signerMintsAndTokenSupplyKeyHasTheIntermediaryContractId";
    private static final String SIGNER_MINTS_WITH_THRESHOLD_KEY = "tokenAndSignerHaveThresholdKey";
    private static final String SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID =
            "signerMintsAndTokenSupplyKeyHasTheSignerPublicKeyAndTheWrongContractId";
    private static final String TREASURY_MINTS = "treasuryIsSignerWithUpdatedKeys";
    private static final String SIGNER_AND_PAYER_ARE_DIFFERENT = "signerAndPayerAreDifferentAccounts";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String TEST_METADATA_1 = "Test metadata 1";
    private static final String MINT_TOKEN_VIA_DELEGATE_CALL = "MixedMintToken";
    private static final String MINT_TOKEN_VIA_STATIC_CALL = "MixedMintToken";
    private static final String MINT_TOKEN_VIA_CALLCODE = "MixedMintToken";
    private static final String MINT_TOKEN_VIA_NESTED_STATIC_CALL = "StaticContract";
    private static final String SERVICE_CONTRACT = "ServiceContract";
    static final byte[][] EMPTY_METADATA = new byte[][] {};
    static final byte[][] TEST_METADATA_2 = new byte[][] {TEST_METADATA_1.getBytes()};

    @HapiTest
    final Stream<DynamicTest> V2Security002FungibleTokenMintInTreasuryPositive() {
        final var amount = 10L;
        final AtomicReference<TokenID> fungible = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SIGNER2),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> fungible.set(asToken(idLit))),
                uploadInitCode(HTS_CALLS),
                contractCreate(HTS_CALLS),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Create a key with shape contract and the contractId of HTSCalls contract
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS)),
                        // Update the token supply key to with the created threshold key
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 1: Signer paying and signing a token mint transaction, where the token
                        // will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer will have a key with the contractId (key type CONTRACT)
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(SIGNER_MINTS_WITH_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER)
                                .signedBy(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, amount),
                        // Test Case 2: the Treasury account is paying and signing a token mint transaction,
                        // where the token will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // NOTE: the only prerequisite in this case is the token to be updated with the
                        // id of the contract calling the precompile which we did for the previous test
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(TREASURY_MINTS)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(2 * amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 2 * amount),
                        // Test Case 3: one account  paying and another one signing a token mint transaction,
                        // where the token will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(SIGNER_AND_PAYER_ARE_DIFFERENT)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER2, TOKEN_TREASURY)
                                .refusingEthConversion()
                                .payingWith(SIGNER2)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(3 * amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 3 * amount),
                        // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of HTSCalls contract
                        newKeyNamed(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, HTS_CALLS))),
                        tokenUpdate(FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Update the transaction signer to have the new threshold key - the newly generated
                        // ed25519 key from the threshold key will be set as the public key of the updated account
                        cryptoUpdate(SIGNER).key(TRESHOLD_KEY_CORRECT_CONTRACT_ID),
                        // Test Case 4: a signer account paying and signing a token mint transaction,
                        // where the token will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer will have a key with the contractId (key type CONTRACT)
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(SIGNER_MINTS_WITH_THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(4 * amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 4 * amount))),
                // Verify that each test case has 1 successful child record
                getTxnRecord(SIGNER_MINTS_WITH_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)),
                getTxnRecord(TREASURY_MINTS)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)),
                getTxnRecord(SIGNER_AND_PAYER_ARE_DIFFERENT)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)),
                getTxnRecord(SIGNER_MINTS_WITH_THRESHOLD_KEY)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security003NonFungibleTokenMintInTreasuryPositive() {
        final var amount = 1;
        final AtomicReference<TokenID> nonFungible = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SIGNER2),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> nonFungible.set(asToken(idLit))),
                uploadInitCode(HTS_CALLS),
                contractCreate(HTS_CALLS),
                uploadInitCode(MINT_CONTRACT),
                sourcing(() ->
                        contractCreate(MINT_CONTRACT, HapiParserUtil.asHeadlongAddress(asAddress(nonFungible.get())))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Create a key with shape contract and the contractId of HTSCalls contract
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(HTS_CALLS)),
                        tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 1: Signer paying and signing a token mint transaction, where the token
                        // will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer will have a key with the contractId (key type CONTRACT)
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(SIGNER_MINTS_WITH_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .payingWith(SIGNER)
                                .signedBy(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, amount),
                        // Test Case 2: Treasury account is paying and signing a token mint transaction,
                        // where the token will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // NOTE: the only prerequisite in this case is the token to be updated with the
                        // id of the contract calling the precompile which we did for the previous test
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(TREASURY_MINTS)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(2 * amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2 * amount),
                        // Test Case 3: one account  paying and another one signing a token mint transaction,
                        // where the token will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(SIGNER_AND_PAYER_ARE_DIFFERENT)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER2, TOKEN_TREASURY)
                                .payingWith(SIGNER2)
                                .refusingEthConversion()
                                .hasRetryPrecheckFrom(BUSY),
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(3 * amount),
                        tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(3 * amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 3 * amount),
                        // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of HTSCalls contract
                        newKeyNamed(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, HTS_CALLS))),
                        tokenUpdate(NON_FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Update the transaction signer to have the new threshold key - the newly generated
                        // ed25519 key from the threshold key will be set as the public key of the updated account
                        cryptoUpdate(SIGNER).key(TRESHOLD_KEY_CORRECT_CONTRACT_ID),
                        // Test Case 4: a signer account paying and signing a token mint transaction,
                        // where the token will be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer and the token will have a key with the contractId and the signer public key
                        // (key with thresh 1/2 with ED25519 and CONTRACT)
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(SIGNER_MINTS_WITH_THRESHOLD_KEY)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is minted - total supply should be increased
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(4 * amount),
                        // Assert the token is mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 4 * amount))),
                // Verify that each test case has 1 successful child record
                getTxnRecord(SIGNER_MINTS_WITH_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)),
                getTxnRecord(TREASURY_MINTS)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)),
                getTxnRecord(SIGNER_AND_PAYER_ARE_DIFFERENT)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)),
                getTxnRecord(SIGNER_MINTS_WITH_THRESHOLD_KEY)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(SUCCESS)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security002FungibleTokenMintInTreasuryNegative() {
        final var amount = 10L;
        final AtomicReference<TokenID> fungible = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> fungible.set(asToken(idLit))),
                uploadInitCode(HTS_CALLS),
                contractCreate(HTS_CALLS),
                uploadInitCode(MINT_CONTRACT),
                sourcing(() ->
                        contractCreate(MINT_CONTRACT, HapiParserUtil.asHeadlongAddress(asAddress(fungible.get())))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // Test Case 1: Signer paying and signing a token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer and the token don't have updated keys
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0L),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                        // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of MINT_CONTRACT
                        // contract
                        // Here the key has the contract`id of the wring contract
                        newKeyNamed(TRESHOLD_KEY_WITH_SIGNER_KEY)
                                .shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_CONTRACT))),
                        // Update the signer of the transaction to have the threshold key with the wotng contract id
                        cryptoUpdate(SIGNER).key(TRESHOLD_KEY_WITH_SIGNER_KEY),
                        // Update the token's supply to have the threshold key witht he wrong contract id
                        tokenUpdate(FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_WITH_SIGNER_KEY)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 2: Signer paying and signing a token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer and the token have a threshold key with the signer's public key
                        // and the wrong contract id
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0L),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                        // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of HTS_CALLS contract
                        // Here the key has the contract`id of the correct contract
                        newKeyNamed(THRESHOLD_KEY).shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, HTS_CALLS))),
                        // Set the token's supply key to the initial one
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(TOKEN_TREASURY).signedByPayerAnd(TOKEN_TREASURY),
                        // Update the Signer with the correct threshold key
                        cryptoUpdate(SIGNER).key(THRESHOLD_KEY),
                        // Test Case 3: Signer paying and signing a token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The token has no updated supply key. The signer has the correct threshold key
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(amount),
                                        new byte[][] {})
                                .via(TOKEN_HAS_NO_UPDATED_KEY)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0L),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L))),
                // Verify that each test case has 1 child record with the correct error message
                getTxnRecord(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTxnRecord(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTxnRecord(TOKEN_HAS_NO_UPDATED_KEY)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security003NonFungibleTokenMintInTreasuryNegative() {
        final AtomicReference<TokenID> nonFungible = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> nonFungible.set(asToken(idLit))),
                uploadInitCode(HTS_CALLS),
                contractCreate(HTS_CALLS),
                uploadInitCode(MINT_CONTRACT),
                sourcing(() ->
                        contractCreate(MINT_CONTRACT, HapiParserUtil.asHeadlongAddress(asAddress(nonFungible.get())))),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(THRESHOLD_KEY).shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, HTS_CALLS))),
                        // Test Case 1: Signer paying and signing a token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer and the token don't have updated keys
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of MINT_CONTRACT
                        // contract
                        // Here the key has the contract`id of the wring contract
                        newKeyNamed(TRESHOLD_KEY_WITH_SIGNER_KEY)
                                .shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_CONTRACT))),
                        // Update the signer of the transaction to have the threshold key with the wotng contract id
                        cryptoUpdate(SIGNER).key(TRESHOLD_KEY_WITH_SIGNER_KEY),
                        // Update the token's supply to have the threshold key with he wrong contract id
                        tokenUpdate(NON_FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_WITH_SIGNER_KEY)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 2: Signer paying and signing a token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The signer and the token have a threshold key with the signer's public key
                        // and the wrong contract id
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        // Set the token's supply key to the initial one
                        tokenUpdate(NON_FUNGIBLE_TOKEN)
                                .supplyKey(TOKEN_TREASURY)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Update the Signer with the correct threshold key
                        cryptoUpdate(SIGNER).key(THRESHOLD_KEY),
                        // Test Case 3: Signer paying and signing a token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → call → PRECOMPILE
                        // The token has no updated supply key. The signer has the correct threshold key
                        contractCall(
                                        HTS_CALLS,
                                        "mintTokenCall",
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        BigInteger.valueOf(0L),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(TOKEN_HAS_NO_UPDATED_KEY)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0L),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L))),
                // Verify that each test case has 1 child record with the correct error message
                getTxnRecord(SIGNER_AND_TOKEN_HAVE_NO_UPDATED_KEYS)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTxnRecord(SIGNER_MINTS_WITH_SIGNER_PUBLIC_KEY_AND_WRONG_CONTRACT_ID)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTxnRecord(TOKEN_HAS_NO_UPDATED_KEY)
                        .andAllChildRecords()
                        .hasChildRecords(recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security035TokenWithDelegateContractKeyCanNotMintFromDelegatecall() {
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY),
                uploadInitCode(MINT_TOKEN_VIA_DELEGATE_CALL),
                contractCreate(MINT_TOKEN_VIA_DELEGATE_CALL),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MINT_TOKEN_VIA_DELEGATE_CALL)),
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 1: Treasury account paying and signing a FUNGIBLE token mint transaction, when the
                        // token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                        // The token has updated key
                        contractCall(
                                        MINT_TOKEN_VIA_DELEGATE_CALL,
                                        "mintTokenDelegateCall",
                                        BigInteger.valueOf(1L),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        new byte[][] {})
                                .via(DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                        // Test Case 2: Treasury account paying and signing a NON FUNGIBLE token mint transaction, when
                        // the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                        // The token has updated key
                        contractCall(
                                        MINT_TOKEN_VIA_DELEGATE_CALL,
                                        "mintTokenDelegateCall",
                                        BigInteger.valueOf(0L),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        // Create a key with thresh 1/2 with sigs:  new ed25519 key, contractId of
                        // MINT_TOKEN_VIA_DELEGATE_CALL contract
                        newKeyNamed(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_TOKEN_VIA_DELEGATE_CALL))),
                        // Update the token's supply to have the threshold key with he wrong contract id
                        tokenUpdate(NON_FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Update the signer of the transaction to have the threshold key with the wotng contract id
                        cryptoUpdate(SIGNER).key(TRESHOLD_KEY_CORRECT_CONTRACT_ID),
                        // Test Case 3: A Signer paying and signing a NON FUNGIBLE token mint transaction, when the
                        // token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                        // The token and the signer have updated keys
                        contractCall(
                                        MINT_TOKEN_VIA_DELEGATE_CALL,
                                        "mintTokenDelegateCall",
                                        BigInteger.valueOf(0L),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        tokenUpdate(NON_FUNGIBLE_TOKEN)
                                .supplyKey(TRESHOLD_KEY_CORRECT_CONTRACT_ID)
                                .signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 4: A Signer paying and signing a FUNGIBLE token mint transaction, when the token
                        // is expected to  be minted in the token treasury account
                        // SIGNER → call → CONTRACT → delegatecall → PRECOMPILE
                        // The token and the signer have updated keys
                        contractCall(
                                        MINT_TOKEN_VIA_DELEGATE_CALL,
                                        "mintTokenDelegateCall",
                                        BigInteger.valueOf(1L),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        new byte[][] {})
                                .via(DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS)
                                .gas(GAS_TO_OFFER)
                                .signedBy(SIGNER)
                                .payingWith(SIGNER)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L))),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            // Verify that each test case has 1 top level call with the correct status
                            // NOTE: the used contract will revert when th token is not mined.
                            // The receipt has the revert error message.
                            childRecordsCheck(
                                    DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED),
                            childRecordsCheck(
                                    DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED),
                            childRecordsCheck(
                                    DELEGATE_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS,
                                    CONTRACT_REVERT_EXECUTED),
                            childRecordsCheck(
                                    DELEGATE_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID_SIGNER_SIGNS,
                                    CONTRACT_REVERT_EXECUTED));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security040TokenWithDelegateContractKeyCanNotMintFromStaticcall() {
        final AtomicReference<TokenID> fungible = new AtomicReference<>();
        final AtomicReference<TokenID> nonFungible = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(TOKEN_TREASURY).balance(ONE_MILLION_HBARS),
                cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> fungible.set(asToken(idLit))),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> nonFungible.set(asToken(idLit))),
                uploadInitCode(MINT_TOKEN_VIA_STATIC_CALL, MINT_TOKEN_VIA_NESTED_STATIC_CALL, SERVICE_CONTRACT),
                contractCreate(MINT_TOKEN_VIA_STATIC_CALL),
                contractCreate(SERVICE_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                MINT_TOKEN_VIA_NESTED_STATIC_CALL,
                                asHeadlongAddress(getNestedContractAddress(SERVICE_CONTRACT, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(CONTRACT.signedWith(MINT_TOKEN_VIA_NESTED_STATIC_CALL)),
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        // Test Case 1: Treasury account paying and signing a fungible TOKEN MINT TRANSACTION,
                        // when the token is expected to be minted in the token treasury account
                        // fails with the mintTokenStaticCall function revert message in the receipt
                        // SIGNER -> call -> CONTRACT -> staticcall -> PRECOMPILE
                        contractCall(
                                        MINT_TOKEN_VIA_STATIC_CALL,
                                        "mintTokenStaticCall",
                                        BigInteger.valueOf(1L),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN))),
                                        new byte[][] {})
                                .via(STATIC_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                        // Test Case 2: Treasury account paying and signing a non fungible TOKEN MINT TRANSACTION,
                        // when the token is expected to be minted in the token treasury account
                        // SIGNER -> call -> CONTRACT -> staticcall -> PRECOMPILE
                        contractCall(
                                        MINT_TOKEN_VIA_STATIC_CALL,
                                        "mintTokenStaticCall",
                                        BigInteger.valueOf(0L),
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))),
                                        new byte[][] {TEST_METADATA_1.getBytes()})
                                .via(STATIC_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L))),
                emptyChildRecordsCheck(STATIC_CALL_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED),
                emptyChildRecordsCheck(STATIC_CALL_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security040TokenWithDelegateContractKeyCanNotMintFromCallcode() {
        final AtomicReference<TokenID> fungible = new AtomicReference<>();
        final AtomicReference<TokenID> nonFungible = new AtomicReference<>();
        final String precompileAddress = "0000000000000000000000000000000000000167";

        return hapiTest(
                newKeyNamed(THRESHOLD_KEY),
                cryptoCreate(TOKEN_TREASURY).key(THRESHOLD_KEY).balance(THOUSAND_HBAR),
                cryptoCreate(RECEIVER),
                cryptoCreate(SIGNER).balance(THOUSAND_HBAR),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> fungible.set(asToken(idLit))),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .supplyKey(TOKEN_TREASURY)
                        .exposingCreatedIdTo(idLit -> nonFungible.set(asToken(idLit))),
                uploadInitCode(MINT_TOKEN_VIA_CALLCODE),
                contractCreate(MINT_TOKEN_VIA_CALLCODE),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY)
                                .shape(TRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_TOKEN_VIA_CALLCODE))),
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        cryptoUpdate(TOKEN_TREASURY).key(CONTRACT_KEY),
                        // Test Case 1: Treasury account paying and signing a fungible TOKEN MINT TRANSACTION,
                        // when the token is expected to be minted in the token treasury account
                        // SIGNER -> call -> CONTRACT -> callcode -> PRECOMPILE
                        contractCall(
                                        MINT_TOKEN_VIA_CALLCODE,
                                        "callCodeToContractWithoutAmount",
                                        asHeadlongAddress(precompileAddress),
                                        Bytes.wrap(MintTranslator.MINT_V2
                                                        .encodeCallWithArgs(
                                                                asHeadlongAddress(
                                                                        asAddress(
                                                                                spec.registry()
                                                                                        .getTokenID(FUNGIBLE_TOKEN))),
                                                                1L,
                                                                EMPTY_METADATA)
                                                        .array())
                                                .toArray())
                                .sending(ONE_HUNDRED_HBARS)
                                .via(CALLCODE_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                        // `THRESHOLD_KEY` is the already existing admin key for `NON_FUNGIBLE_TOKEN` and we need to
                        // sign with it
                        tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(THRESHOLD_KEY),
                        // Test Case 2: Treasury account paying and signing a non fungible TOKEN MINT TRANSACTION,
                        // when the token is expected to be minted in the token treasury account
                        // SIGNER -> call -> CONTRACT -> callcode -> PRECOMPILE
                        contractCall(
                                        MINT_TOKEN_VIA_CALLCODE,
                                        "callCodeToContractWithoutAmount",
                                        asHeadlongAddress("0000000000000000000000000000000000000167"),
                                        Bytes.wrap(MintTranslator.MINT_V2
                                                        .encodeCallWithArgs(
                                                                asHeadlongAddress(
                                                                        asAddress(
                                                                                spec.registry()
                                                                                        .getTokenID(
                                                                                                NON_FUNGIBLE_TOKEN))),
                                                                1L,
                                                                TEST_METADATA_2)
                                                        .array())
                                                .toArray())
                                .sending(ONE_HUNDRED_HBARS)
                                .via(CALLCODE_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID)
                                .gas(GAS_TO_OFFER)
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .hasRetryPrecheckFrom(BUSY)
                                // Verify that the top level status of the transaction is CONTRACT_REVERT_EXECUTED
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        // Assert that the token is NOT minted - total supply should be 0
                        getTokenInfo(NON_FUNGIBLE_TOKEN).hasTotalSupply(0),
                        // Assert the token is NOT mined in the token treasury account
                        getAccountBalance(TOKEN_TREASURY).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L))),
                childRecordsCheck(CALLCODE_WHEN_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED),
                childRecordsCheck(CALLCODE_WHEN_NON_FUNGIBLE_TOKEN_HAS_CONTRACT_ID, CONTRACT_REVERT_EXECUTED));
    }
}
