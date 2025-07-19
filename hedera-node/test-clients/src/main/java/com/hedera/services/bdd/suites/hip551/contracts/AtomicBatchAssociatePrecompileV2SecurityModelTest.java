// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsTranslator.ASSOCIATE_ONE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getNestedContractAddress;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.FreezeNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Unfrozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.KycNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchAssociatePrecompileV2SecurityModelTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    private static final long GAS_TO_OFFER = 6_000_000L;
    private static final String TOKEN_TREASURY = "treasury";
    public static final String ASSOCIATE_CONTRACT = "AssociateDissociate";
    public static final String NESTED_ASSOCIATE_CONTRACT = "NestedAssociateDissociate";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);
    private static final KeyShape DELEGATE_CONTRACT_KEY_SHAPE = KeyShape.threshOf(1, ED25519, DELEGATE_CONTRACT);
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String ACCOUNT = "anybody";
    private static final String FROZEN_TOKEN = "Frozen token";
    private static final String UNFROZEN_TOKEN = "Unfrozen token";
    private static final String KYC_TOKEN = "KYC token";
    private static final String FREEZE_KEY = "Freeze key";
    private static final String KYC_KEY = "KYC key";
    private static final String CONTRACT_KEY = "ContractKey";
    private static final String MINT_TOKEN_CONTRACT = "MixedMintToken";
    private static final String CALLCODE_CONTRACT = "MixedMintToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "atomicBatch.isEnabled", "true",
                "atomicBatch.maxNumberOfTransactions", "50",
                "contracts.throttle.throttleByGas", "false"));
        testLifecycle.doAdhoc(
                cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                // create contracts
                uploadInitCode(ASSOCIATE_CONTRACT, NESTED_ASSOCIATE_CONTRACT, MINT_TOKEN_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT),
                contractCreate(MINT_TOKEN_CONTRACT),
                // create accounts
                newKeyNamed(FREEZE_KEY),
                newKeyNamed(KYC_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> associateSingleFungibleTokenWithDelegateContractKey() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                // create tokens and signer
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                // add contract id key to the signer
                newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                sourcing(() -> atomicBatchDefaultOperator(
                        // Test Case 1: Account paying and signing a fungible TOKEN ASSOCIATE TRANSACTION,
                        // when signer has a threshold key
                        // associating ACCOUNT to the token
                        // SIGNER → call → CONTRACT A → call → HTS
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        contractCall(ASSOCIATE_CONTRACT, "tokenAssociate", accountAddress.get(), fungibleAddress.get())
                                .payingWith(ACCOUNT)
                                .via("fungibleTokenAssociate")
                                .gas(GAS_TO_OFFER))),
                getAccountInfo(ACCOUNT)
                        .hasToken(relationshipWith(FUNGIBLE_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(FreezeNotApplicable)));
    }

    @HapiTest
    final Stream<DynamicTest> associateSingleNftWithDelegateContractKey() {
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                // create tokens and signer
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nftAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                // add contract id key to the signer
                newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                // perform contract call in a batch
                sourcing(() -> atomicBatchDefaultOperator(
                        // Test Case 2: Account paying and signing a non fungible TOKEN ASSOCIATE TRANSACTION,
                        // when signer has a threshold key
                        // associating ACCOUNT to the token
                        // SIGNER → call → CONTRACT A → call → HTS
                        tokenUpdate(NON_FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        contractCall(ASSOCIATE_CONTRACT, "tokenAssociate", accountAddress.get(), nftAddress.get())
                                .payingWith(ACCOUNT)
                                .via("nonFungibleTokenAssociate")
                                .gas(GAS_TO_OFFER))),
                getAccountInfo(ACCOUNT)
                        .hasToken(relationshipWith(NON_FUNGIBLE_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(FreezeNotApplicable)));
    }

    @HapiTest
    final Stream<DynamicTest> associateMultipleTokensWithDelegateContractKey() {
        final AtomicReference<Address> frozenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> unfrozenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> kycTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                // create tokens and a signer
                createFrozenTokenAndExposeAdr(FROZEN_TOKEN, frozenTokenAddress, true),
                createFrozenTokenAndExposeAdr(UNFROZEN_TOKEN, unfrozenTokenAddress, false),
                createKycTokenAndExposeAdr(KYC_TOKEN, kycTokenAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                // add contract id key to the signer
                newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_CONTRACT))),
                cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                sourcing(() -> atomicBatchDefaultOperator(
                        // Test Case 3: Account paying and signing a multiple TOKENS ASSOCIATE TRANSACTION,
                        // when signer has a threshold key
                        // SIGNER → call → CONTRACT A → call → HTS
                        tokenUpdate(FROZEN_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        tokenUpdate(UNFROZEN_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        tokenUpdate(KYC_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        contractCall(ASSOCIATE_CONTRACT, "tokensAssociate", accountAddress.get(), new Address[] {
                                    frozenTokenAddress.get(), unfrozenTokenAddress.get(), kycTokenAddress.get(),
                                })
                                .payingWith(ACCOUNT)
                                .via("multipleTokensAssociate")
                                .gas(GAS_TO_OFFER))),
                getAccountInfo(ACCOUNT)
                        .hasToken(relationshipWith(FROZEN_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(Frozen))
                        .hasToken(relationshipWith(UNFROZEN_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(Unfrozen))
                        .hasToken(relationshipWith(KYC_TOKEN).kyc(Revoked).freeze(FreezeNotApplicable)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateFungibleTokenNegative() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();

        return hapiTest(
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                // Test Case 1: SIGNER account  paying and signing a fungible TOKEN ASSOCIATE TRANSACTION,
                // associating token to ACCOUNT
                // SIGNER → call → CONTRACT A → call → HTS
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_CONTRACT,
                                        "tokenAssociate",
                                        accountAddress.get(),
                                        fungibleAddress.get())
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .payingWith(ACCOUNT)
                                .via("fungibleTokenAssociate")
                                .gas(GAS_TO_OFFER))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                childRecordsCheck(
                        "fungibleTokenAssociate",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateNftNegative() {
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nftAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                // Test Case 2: SIGNER account  paying and signing a non fungible TOKEN ASSOCIATE TRANSACTION,
                // associating to ACCOUNT
                // SIGNER → call → CONTRACT A → call → HTS
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_CONTRACT, "tokenAssociate", accountAddress.get(), nftAddress.get())
                                .payingWith(ACCOUNT)
                                .via("nonFungibleTokenAssociate")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                childRecordsCheck(
                        "nonFungibleTokenAssociate",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateMultipleTokensNegative() {
        final AtomicReference<Address> frozenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> unfrozenTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> kycTokenAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();

        return hapiTest(
                createFrozenTokenAndExposeAdr(FROZEN_TOKEN, frozenTokenAddress, true),
                createFrozenTokenAndExposeAdr(UNFROZEN_TOKEN, unfrozenTokenAddress, false),
                createKycTokenAndExposeAdr(KYC_TOKEN, kycTokenAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),

                // Test Case 3: SIGNER account  paying and signing multiple TOKENS ASSOCIATE TRANSACTION,
                // associating to ЕОА ACCOUNT
                // SIGNER → call → CONTRACT A → call → HTS
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        ASSOCIATE_CONTRACT, "tokensAssociate", accountAddress.get(), new Address[] {
                                            frozenTokenAddress.get(), unfrozenTokenAddress.get(), kycTokenAddress.get(),
                                        })
                                .payingWith(ACCOUNT)
                                .via("multipleTokensAssociate")
                                .gas(GAS_TO_OFFER)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                childRecordsCheck(
                        "multipleTokensAssociate",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateFromNestedContractNegative() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        // create nested contract
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        // Test Case 4: SIGNER account  paying and signing nested TOKEN ASSOCIATE TRANSACTION,
                        // associating to ЕОА ACCOUNT
                        // SIGNER → call → CONTRACT A → call → HTS
                        atomicBatchDefaultOperator(contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateInternalContractCall",
                                                accountAddress.get(),
                                                fungibleAddress.get())
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateFungibleTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                childRecordsCheck(
                        "nestedAssociateFungibleTxn",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateTokenWithContractNegative() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                // Test Case 5: SIGNER account paying and signing a fungible TOKEN ASSOCIATE TRANSACTION,
                // associating to CONTRACT
                // when signer has a threshold key
                // SIGNER → call → CONTRACT A → call → HTS
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_TOKEN_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        atomicBatchDefaultOperator(contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokenAssociate",
                                                asHeadlongAddress(getNestedContractAddress(MINT_TOKEN_CONTRACT, spec)),
                                                fungibleAddress.get())
                                        .payingWith(ACCOUNT)
                                        .via("associateTokenToContractFails")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED))),
                childRecordsCheck(
                        "associateTokenToContractFails",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @HapiTest
    final Stream<DynamicTest> nestedAssociateNftAndNonFungibleTokens() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();

        return hapiTest(flattened(
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nftAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        atomicBatchDefaultOperator(
                                // Test Case 1: Account paying and signing a nested fungible TOKEN ASSOCIATE
                                // TRANSACTION,
                                // when we associate the token to the signer
                                // SIGNER → call → CONTRACT A → call → CONTRACT B → call → PRECOMPILE(HTS)
                                contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateInternalContractCall",
                                                accountAddress.get(),
                                                fungibleAddress.get())
                                        .signedBy(ACCOUNT)
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateFungibleTxn")
                                        .gas(GAS_TO_OFFER),
                                // Test Case 2: Account paying and signing a nested non fungible TOKEN ASSOCIATE
                                // TRANSACTION,
                                // when we associate the token to the signer
                                // SIGNER → call → CONTRACT A → call → CONTRACT B → call → PRECOMPILE(HTS)
                                contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateInternalContractCall",
                                                accountAddress.get(),
                                                nftAddress.get())
                                        .signedBy(ACCOUNT)
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateNonFungibleTxn")
                                        .gas(GAS_TO_OFFER)))),
                getAccountInfo(ACCOUNT)
                        .hasToken(relationshipWith(FUNGIBLE_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(FreezeNotApplicable))
                        .hasToken(relationshipWith(NON_FUNGIBLE_TOKEN)
                                .kyc(KycNotApplicable)
                                .freeze(FreezeNotApplicable)),
                getTxnRecord("nestedAssociateFungibleTxn")
                        .andAllChildRecords()
                        .hasChildRecords(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS)))),
                getTxnRecord("nestedAssociateNonFungibleTxn")
                        .andAllChildRecords()
                        .hasChildRecords(recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(SUCCESS))))));
    }

    @HapiTest
    final Stream<DynamicTest> V2Security036TokenAssociateFromDelegateCallWithDelegateContractId() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> nftAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();
        return hapiTest(
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                createNftAndExposeAdr(NON_FUNGIBLE_TOKEN, nftAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        // SIGNER → call → CONTRACT A → delegatecall → CONTRACT B → call → PRECOMPILE(HTS)
                        newKeyNamed(CONTRACT_KEY)
                                .shape(DELEGATE_CONTRACT_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        atomicBatchDefaultOperator(
                                contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateDelegateCall",
                                                accountAddress.get(),
                                                fungibleAddress.get())
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateFungibleTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(SUCCESS),

                                // non fungible token
                                contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateDelegateCall",
                                                accountAddress.get(),
                                                nftAddress.get())
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateNonFungibleTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(SUCCESS)),
                        getAccountInfo(ACCOUNT)
                                .hasToken(relationshipWith(FUNGIBLE_TOKEN)
                                        .kyc(KycNotApplicable)
                                        .freeze(FreezeNotApplicable))
                                .hasToken(relationshipWith(NON_FUNGIBLE_TOKEN)
                                        .kyc(KycNotApplicable)
                                        .freeze(FreezeNotApplicable)),
                        getTxnRecord("nestedAssociateFungibleTxn")
                                .andAllChildRecords()
                                .hasChildRecords(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(
                                                        htsPrecompileResult().withStatus(SUCCESS)))),
                        getTxnRecord("nestedAssociateNonFungibleTxn")
                                .andAllChildRecords()
                                .hasChildRecords(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(
                                                        htsPrecompileResult().withStatus(SUCCESS)))))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateFromStaticcallAndCallcode() {
        final AtomicReference<Address> fungibleAddress = new AtomicReference<>();
        final AtomicReference<Address> accountAddress = new AtomicReference<>();

        return hapiTest(flattened(
                createFungibleAndExposeAdr(FUNGIBLE_TOKEN, fungibleAddress),
                createAccountAndExposeAddr(ACCOUNT, accountAddress),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        // SIGNER → call → CONTRACT A → staticcall → CONTRACT B → call → PRECOMPILE(HTS)
                        newKeyNamed(CONTRACT_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        // Test Case 1: Account paying and signing a nested fungible TOKEN ASSOCIATE
                        // TRANSACTION,
                        // when we associate the token to the signer
                        // via STATIC CALL
                        atomicBatchDefaultOperator(contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateStaticCall",
                                                accountAddress.get(),
                                                fungibleAddress.get())
                                        .payingWith(ACCOUNT)
                                        .via("associateStaticcallFungibleTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // Test Case 2: Account paying and signing a nested fungible TOKEN ASSOCIATE TRANSACTION,
                        // when we associate the token to the signer
                        // via CALL CODE
                        // SIGNER → call → CONTRACT A → callcode → CONTRACT B → call → PRECOMPILE(HTS)
                        atomicBatchDefaultOperator(contractCall(
                                                CALLCODE_CONTRACT,
                                                "callCodeToContractWithoutAmount",
                                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec)),
                                                Bytes.wrap(ASSOCIATE_ONE
                                                                .encodeCallWithArgs(
                                                                        accountAddress.get(), fungibleAddress.get())
                                                                .array())
                                                        .toArray())
                                        .via("associateCallcodeFungibleTxn")
                                        .gas(GAS_TO_OFFER)
                                        .sending(ONE_HUNDRED_HBARS)
                                        .payingWith(TOKEN_TREASURY)
                                        // Verify that the top level status of the transaction is
                                        // CONTRACT_REVERT_EXECUTED
                                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        emptyChildRecordsCheck("associateStaticcallFungibleTxn", CONTRACT_REVERT_EXECUTED),
                        emptyChildRecordsCheck("associateCallcodeFungibleTxn", CONTRACT_REVERT_EXECUTED),
                        getAccountInfo(ACCOUNT).hasNoTokenRelationship(FUNGIBLE_TOKEN)))));
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

    private HapiTokenCreate createFrozenTokenAndExposeAdr(
            String tokenName, AtomicReference<Address> addressRef, boolean freezeDefault) {
        final var create = createFungibleAndExposeAdr(tokenName, addressRef);
        create.freezeKey(FREEZE_KEY).freezeDefault(freezeDefault);
        return create;
    }

    private HapiTokenCreate createKycTokenAndExposeAdr(String tokenName, AtomicReference<Address> addressRef) {
        final var create = createFungibleAndExposeAdr(tokenName, addressRef);
        create.kycKey(KYC_KEY);
        return create;
    }

    private HapiCryptoCreate createAccountAndExposeAddr(String accountName, AtomicReference<Address> addressRef) {
        return cryptoCreate(accountName)
                .balance(ONE_MILLION_HBARS)
                .exposingCreatedIdTo(id -> addressRef.set(asHeadlongAddress(asAddress(id))));
    }
}
