// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.associations.AssociationsTranslator;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchAssociatePrecompileV2SecurityModelTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    private static final long GAS_TO_OFFER = 6_000_000L;
    private static final long TOTAL_SUPPLY = 1_000;
    private static final String SIGNER = "anybody";
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
                "atomicBatch.isEnabled",
                "true",
                "atomicBatch.maxNumberOfTransactions",
                "50",
                "contracts.throttle.throttleByGas",
                "false"));
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        testLifecycle.doAdhoc(
                uploadInitCode(ASSOCIATE_CONTRACT, NESTED_ASSOCIATE_CONTRACT, MINT_TOKEN_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT),
                contractCreate(MINT_TOKEN_CONTRACT));
    }

    // Create keys and accounts FREEZE_KEY, KYC_KEY, TOKEN_TREASURY, SIGNER and ACCOUNT
    // Create tokens FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, FROZEN_TOKEN, UNFROZEN_TOKEN and KYC_TOKEN
    private static HapiSpecOperation[] createEntities() {
        return List.of(
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        cryptoCreate(TOKEN_TREASURY).balance(ONE_MILLION_HBARS),
                        cryptoCreate(SIGNER).balance(ONE_MILLION_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_MILLION_HBARS),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(TokenType.FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .supplyKey(TOKEN_TREASURY)
                                .adminKey(TOKEN_TREASURY),
                        tokenCreate(NON_FUNGIBLE_TOKEN)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .treasury(TOKEN_TREASURY)
                                .adminKey(TOKEN_TREASURY)
                                .supplyKey(TOKEN_TREASURY),
                        tokenCreate(FROZEN_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(TOTAL_SUPPLY)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(true)
                                .adminKey(TOKEN_TREASURY)
                                .supplyKey(TOKEN_TREASURY),
                        tokenCreate(UNFROZEN_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .freezeKey(FREEZE_KEY)
                                .freezeDefault(false)
                                .adminKey(TOKEN_TREASURY)
                                .supplyKey(TOKEN_TREASURY),
                        tokenCreate(KYC_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .kycKey(KYC_KEY)
                                .adminKey(TOKEN_TREASURY)
                                .supplyKey(TOKEN_TREASURY))
                .toArray(new HapiSpecOperation[0]);
    }

    @HapiTest
    final Stream<DynamicTest> associateSingleTokenWithDelegateContractKey() {
        return hapiTest(flattened(
                // Create keys and accounts FREEZE_KEY, KYC_KEY, TOKEN_TREASURY, SIGNER and ACCOUNT
                // Create tokens FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, FROZEN_TOKEN, UNFROZEN_TOKEN and KYC_TOKEN
                createEntities(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, ASSOCIATE_CONTRACT))),
                        cryptoUpdate(SIGNER).key(CONTRACT_KEY),
                        atomicBatchDefaultOperator(
                                tokenUpdate(FUNGIBLE_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                // Test Case 1: Account paying and signing a fungible TOKEN ASSOCIATE TRANSACTION,
                                // when signer has a threshold key
                                // associating ACCOUNT to the token
                                // SIGNER → call → CONTRACT A → call → HTS
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokenAssociate",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                        .payingWith(SIGNER)
                                        .via("fungibleTokenAssociate")
                                        .gas(GAS_TO_OFFER),
                                // Test Case 2: Account paying and signing a non fungible TOKEN ASSOCIATE TRANSACTION,
                                // when signer has a threshold key
                                // associating ACCOUNT to the token
                                // SIGNER → call → CONTRACT A → call → HTS

                                tokenUpdate(NON_FUNGIBLE_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokenAssociate",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
                                        .payingWith(SIGNER)
                                        .via("nonFungibleTokenAssociate")
                                        .gas(GAS_TO_OFFER),
                                // Test Case 3: Account paying and signing a multiple TOKENS ASSOCIATE TRANSACTION,
                                // when signer has a threshold key
                                // SIGNER → call → CONTRACT A → call → HTS

                                tokenUpdate(FROZEN_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                tokenUpdate(UNFROZEN_TOKEN)
                                        .supplyKey(CONTRACT_KEY)
                                        .signedByPayerAnd(TOKEN_TREASURY),
                                tokenUpdate(KYC_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                                contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokensAssociate",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                new Address[] {
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(FROZEN_TOKEN))),
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(UNFROZEN_TOKEN))),
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(KYC_TOKEN))),
                                                })
                                        .payingWith(SIGNER)
                                        .via("multipleTokensAssociate")
                                        .gas(GAS_TO_OFFER)),
                        getAccountInfo(ACCOUNT)
                                .hasToken(relationshipWith(FUNGIBLE_TOKEN)
                                        .kyc(KycNotApplicable)
                                        .freeze(FreezeNotApplicable))
                                .hasToken(relationshipWith(NON_FUNGIBLE_TOKEN)
                                        .kyc(KycNotApplicable)
                                        .freeze(FreezeNotApplicable))
                                .hasToken(relationshipWith(FROZEN_TOKEN)
                                        .kyc(KycNotApplicable)
                                        .freeze(Frozen))
                                .hasToken(relationshipWith(UNFROZEN_TOKEN)
                                        .kyc(KycNotApplicable)
                                        .freeze(Unfrozen))
                                .hasToken(
                                        relationshipWith(KYC_TOKEN).kyc(Revoked).freeze(FreezeNotApplicable))))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateNegativeTests() {
        return hapiTest(flattened(
                // Create keys and accounts FREEZE_KEY, KYC_KEY, TOKEN_TREASURY, SIGNER and ACCOUNT
                // Create tokens FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, FROZEN_TOKEN, UNFROZEN_TOKEN and KYC_TOKEN
                createEntities(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        // Test Case 1: SIGNER account  paying and signing a fungible TOKEN ASSOCIATE TRANSACTION,
                        // associating token to ACCOUNT
                        // SIGNER → call → CONTRACT A → call → HTS
                        atomicBatchDefaultOperator(contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokenAssociate",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                        .payingWith(ACCOUNT)
                                        .via("fungibleTokenAssociate")
                                        .gas(GAS_TO_OFFER))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        childRecordsCheck(
                                "fungibleTokenAssociate",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith()
                                        .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .withStatus(
                                                                INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE))))))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateNegativeTestsPart2() {
        return hapiTest(flattened(
                // Create keys and accounts FREEZE_KEY, KYC_KEY, TOKEN_TREASURY, SIGNER and ACCOUNT
                // Create tokens FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, FROZEN_TOKEN, UNFROZEN_TOKEN and KYC_TOKEN
                createEntities(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),

                        // Test Case 2: SIGNER account  paying and signing a non fungible TOKEN ASSOCIATE TRANSACTION,
                        // associating to ACCOUNT
                        // SIGNER → call → CONTRACT A → call → HTS
                        atomicBatchDefaultOperator(contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokenAssociate",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
                                        .payingWith(ACCOUNT)
                                        .via("nonFungibleTokenAssociate")
                                        .gas(GAS_TO_OFFER))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // Test Case 3: SIGNER account  paying and signing multiple TOKENS ASSOCIATE TRANSACTION,
                        // associating to ЕОА ACCOUNT
                        // SIGNER → call → CONTRACT A → call → HTS
                        atomicBatchDefaultOperator(contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokensAssociate",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                new Address[] {
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(FROZEN_TOKEN))),
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(UNFROZEN_TOKEN))),
                                                    HapiParserUtil.asHeadlongAddress(asAddress(
                                                            spec.registry().getTokenID(KYC_TOKEN)))
                                                })
                                        .payingWith(ACCOUNT)
                                        .via("multipleTokensAssociate")
                                        .gas(GAS_TO_OFFER))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        childRecordsCheck(
                                "nonFungibleTokenAssociate",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith()
                                        .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                        childRecordsCheck(
                                "multipleTokensAssociate",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith()
                                        .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .withStatus(
                                                                INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE))))))));
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateNegativeTestsPart3() {
        return hapiTest(flattened(
                // Create keys and accounts FREEZE_KEY, KYC_KEY, TOKEN_TREASURY, SIGNER and ACCOUNT
                // Create tokens FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, FROZEN_TOKEN, UNFROZEN_TOKEN and KYC_TOKEN
                createEntities(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        // Test Case 4: SIGNER account  paying and signing nested TOKEN ASSOCIATE TRANSACTION,
                        // associating to ЕОА ACCOUNT
                        // SIGNER → call → CONTRACT A → call → HTS
                        atomicBatchDefaultOperator(contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateInternalContractCall",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateFungibleTxn")
                                        .gas(GAS_TO_OFFER))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // Test Case 5: SIGNER account paying and signing a fungible TOKEN ASSOCIATE TRANSACTION,
                        // associating to CONTRACT
                        // when signer has a threshold key
                        // SIGNER → call → CONTRACT A → call → HTS
                        newKeyNamed(CONTRACT_KEY).shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, MINT_TOKEN_CONTRACT))),
                        cryptoUpdate(SIGNER).key(CONTRACT_KEY),
                        tokenUpdate(FUNGIBLE_TOKEN).supplyKey(CONTRACT_KEY).signedByPayerAnd(TOKEN_TREASURY),
                        atomicBatchDefaultOperator(contractCall(
                                                ASSOCIATE_CONTRACT,
                                                "tokenAssociate",
                                                asHeadlongAddress(getNestedContractAddress(MINT_TOKEN_CONTRACT, spec)),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                        .payingWith(SIGNER)
                                        .via("associateTokenToContractFails")
                                        .gas(GAS_TO_OFFER))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        childRecordsCheck(
                                "nestedAssociateFungibleTxn",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith()
                                        .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))),
                        childRecordsCheck(
                                "associateTokenToContractFails",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith()
                                        .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .withStatus(
                                                                INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE))))))));
    }

    @HapiTest
    final Stream<DynamicTest> nestedAssociateNftAndNonFungibleTokens() {
        return hapiTest(flattened(
                // Create keys and accounts FREEZE_KEY, KYC_KEY, TOKEN_TREASURY, SIGNER and ACCOUNT
                // Create tokens FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN, FROZEN_TOKEN, UNFROZEN_TOKEN and KYC_TOKEN
                createEntities(),
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
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))))
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
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
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
        return hapiTest(
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyKey(TOKEN_TREASURY)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(TOKEN_TREASURY)
                        .initialSupply(0)
                        .adminKey(TOKEN_TREASURY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(ASSOCIATE_CONTRACT, NESTED_ASSOCIATE_CONTRACT),
                contractCreate(ASSOCIATE_CONTRACT),
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
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                        .payingWith(ACCOUNT)
                                        .via("nestedAssociateFungibleTxn")
                                        .gas(GAS_TO_OFFER)
                                        .hasKnownStatus(SUCCESS),

                                // non fungible token
                                contractCall(
                                                NESTED_ASSOCIATE_CONTRACT,
                                                "associateDelegateCall",
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getAccountID(ACCOUNT))),
                                                HapiParserUtil.asHeadlongAddress(asAddress(
                                                        spec.registry().getTokenID(NON_FUNGIBLE_TOKEN))))
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

        return hapiTest(flattened(
                createEntities(),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCreate(
                                NESTED_ASSOCIATE_CONTRACT,
                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec))),
                        // SIGNER → call → CONTRACT A → staticcall → CONTRACT B → call → PRECOMPILE(HTS)
                        newKeyNamed(CONTRACT_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, NESTED_ASSOCIATE_CONTRACT))),
                        cryptoUpdate(ACCOUNT).key(CONTRACT_KEY),
                        atomicBatchDefaultOperator(
                                        // Test Case 1: Account paying and signing a nested fungible TOKEN ASSOCIATE
                                        // TRANSACTION,
                                        // when we associate the token to the signer
                                        // via STATICCALL
                                        contractCall(
                                                        NESTED_ASSOCIATE_CONTRACT,
                                                        "associateStaticCall",
                                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                                spec.registry().getAccountID(ACCOUNT))),
                                                        HapiParserUtil.asHeadlongAddress(asAddress(
                                                                spec.registry().getTokenID(FUNGIBLE_TOKEN))))
                                                .payingWith(ACCOUNT)
                                                .via("associateStaticcallFungibleTxn")
                                                .gas(GAS_TO_OFFER)
                                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // Test Case 2: Account paying and signing a nested fungible TOKEN ASSOCIATE TRANSACTION,
                        // when we associate the token to the signer
                        // via CALLCODE
                        // SIGNER → call → CONTRACT A → callcode → CONTRACT B → call → PRECOMPILE(HTS)
                        atomicBatchDefaultOperator(contractCall(
                                                CALLCODE_CONTRACT,
                                                "callCodeToContractWithoutAmount",
                                                asHeadlongAddress(getNestedContractAddress(ASSOCIATE_CONTRACT, spec)),
                                                Bytes.wrap(AssociationsTranslator.ASSOCIATE_ONE
                                                                .encodeCallWithArgs(
                                                                        HapiParserUtil.asHeadlongAddress(
                                                                                asAddress(
                                                                                        spec.registry()
                                                                                                .getAccountID(
                                                                                                        ACCOUNT))),
                                                                        HapiParserUtil.asHeadlongAddress(
                                                                                asAddress(
                                                                                        spec.registry()
                                                                                                .getTokenID(
                                                                                                        FUNGIBLE_TOKEN))))
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

    private HapiAtomicBatch atomicBatchDefaultOperator(HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }
}
