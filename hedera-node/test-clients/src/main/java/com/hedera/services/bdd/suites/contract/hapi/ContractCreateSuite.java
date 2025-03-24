// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isContractWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.DELEGATE_CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.bytecodePath;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicitEthereumTransaction;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertCreationMaxAssociations;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertCreationViaCallMaxAssociations;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.contractListWithPropertiesInheritedFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.getEcdsaPrivateKeyFromSpec;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.CHAIN_ID;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_BYTECODE_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ERROR_DECODING_BYTESTRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.util.Integers;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.utils.Signing;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S1192") // "string literal should not be duplicated" - this rule makes test suites worse
public class ContractCreateSuite {

    public static final String EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";
    public static final String PARENT_INFO = "parentInfo";
    private static final String PAYER = "payer";
    private static final String ALICE = "alice";

    private static final Logger log = LogManager.getLogger(ContractCreateSuite.class);

    // The following constants are referenced from -
    // https://github.com/Arachnid/deterministic-deployment-proxy?tab=readme-ov-file#deployment-transaction
    private static final String DEPLOYMENT_SIGNER = "3fab184622dc19b6109349b94811493bf2a45362";
    private static final String DEPLOYMENT_TRANSACTION =
            "f8a58085174876e800830186a08080b853604580600e600039806000f350fe7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe03601600081602082378035828234f58015156039578182fd5b8082525050506014600cf31ba02222222222222222222222222222222222222222222222222222222222222222a02222222222222222222222222222222222222222222222222222222222222222";
    private static final String EXPECTED_DEPLOYER_ADDRESS = "4e59b44847b379578588920ca78fbf26c0b4956c";
    private static final String DEPLOYER = "DeployerContract";
    public static final String ENTITIES_UNLIMITED_AUTO_ASSOCIATIONS_ENABLED =
            "entities.unlimitedAutoAssociationsEnabled";
    public static final String LEDGER_MAX_AUTO_ASSOCIATIONS = "ledger.maxAutoAssociations";

    private static final String FUNGIBLE_TOKEN = "fungible";
    private static final String MULTI_KEY = "multiKey";

    @HapiTest
    final Stream<DynamicTest> createDeterministicDeployer() {
        final var creatorAddress = ByteString.copyFrom(CommonUtils.unhex(DEPLOYMENT_SIGNER));
        final var transaction = ByteString.copyFrom(CommonUtils.unhex(DEPLOYMENT_TRANSACTION));
        final var systemFileId = FileID.newBuilder().setFileNum(159).build();

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromTo(PAYER, creatorAddress, ONE_HUNDRED_HBARS)),
                explicitEthereumTransaction(DEPLOYER, (spec, b) -> b.setCallData(systemFileId)
                                .setEthereumData(transaction))
                        .payingWith(PAYER),
                getContractInfo(DEPLOYER)
                        .has(contractWith().addressOrAlias(EXPECTED_DEPLOYER_ADDRESS))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> createContractWithStakingFields() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing staking fields to map
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .declinedReward(true)
                        .stakedNodeId(0)
                        .refusingEthConversion(),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(true)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .declinedReward(true)
                        .stakedAccountId("0.0.10")
                        .refusingEthConversion(),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(true)
                                .noStakingNodeId()
                                .stakedAccountId("0.0.10")),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .declinedReward(false)
                        .stakedNodeId(0)
                        .refusingEthConversion(),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakedAccountId()
                                .stakedNodeId(0))
                        .logged(),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .declinedReward(false)
                        .stakedAccountId("0.0.10")
                        .refusingEthConversion(),
                getContractInfo(contract)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .stakedAccountId("0.0.10"))
                        .logged(),
                /* sentinel values throw */
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .declinedReward(false)
                        .stakedAccountId("0.0.0")
                        .hasPrecheck(INVALID_STAKING_ID)
                        .refusingEthConversion(),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .declinedReward(false)
                        .stakedNodeId(-1L)
                        .hasPrecheck(INVALID_STAKING_ID)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> insufficientPayerBalanceUponCreation() {
        return hapiTest(
                cryptoCreate("bankrupt").balance(0L),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .payingWith("bankrupt")
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> disallowCreationsOfEmptyInitCode() {
        final var contract = "EmptyContract";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                // transaction
                contractCreate(contract)
                        .adminKey(ADMIN_KEY)
                        .entityMemo("Empty Contract")
                        .inlineInitCode(ByteString.EMPTY)
                        .hasKnownStatus(CONTRACT_BYTECODE_EMPTY)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> cannotSendToNonExistentAccount() {
        final var contract = "Multipurpose";
        Object[] donationArgs = new Object[] {666666L, "Hey, Ma!"};

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).balance(666),
                contractCall(contract, "donate", donationArgs).hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> invalidSystemInitcodeFileFailsWithInvalidFileId() {
        final var neverToBe = "NeverToBe";
        final var systemFileId = FileID.newBuilder().setFileNum(159).build();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                explicitContractCreate(neverToBe, (spec, b) -> b.setFileID(systemFileId))
                        // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                        // transaction
                        .hasKnownStatus(INVALID_FILE_ID)
                        .refusingEthConversion(),
                explicitEthereumTransaction(neverToBe, (spec, b) -> {
                            final var signedEthTx = Signing.signMessage(
                                    placeholderEthTx(), getEcdsaPrivateKeyFromSpec(spec, SECP_256K1_SOURCE_KEY));
                            b.setCallData(systemFileId).setEthereumData(ByteString.copyFrom(signedEthTx.encodeTx()));
                        })
                        .hasPrecheck(INVALID_FILE_ID));
    }

    @HapiTest
    final Stream<DynamicTest> createsVanillaContractAsExpectedWithOmittedAdminKey() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).omitAdminKey(),
                getContractInfo(EMPTY_CONSTRUCTOR_CONTRACT)
                        .has(contractWith().immutableContractKey(EMPTY_CONSTRUCTOR_CONTRACT))
                        .logged());
    }

    @LeakyHapiTest(overrides = {"ledger.maxAutoAssociations"})
    final Stream<DynamicTest> contractCreationsHaveValidAssociations() {
        final var initCreateContract = "ParentChildTransfer";
        final var slotUserContract = "SlotUser";
        final var multiPurpose = "Multipurpose";
        final var createContract = "CreateTrivial";
        return hapiTest(
                overriding("ledger.maxAutoAssociations", "5000"),
                newKeyNamed(MULTI_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(1000)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .treasury(TOKEN_TREASURY),
                uploadInitCode(initCreateContract, createContract, multiPurpose, slotUserContract),
                contractCreate(initCreateContract)
                        .refusingEthConversion()
                        .via("constructorWithoutExplicitAssociations")
                        .hasKnownStatus(SUCCESS),
                cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(TOKEN_TREASURY, initCreateContract))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                contractCreate(createContract)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(0)
                        .hasKnownStatus(SUCCESS),
                contractCreate(multiPurpose)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(3)
                        .hasKnownStatus(SUCCESS),
                contractCreate(slotUserContract)
                        .refusingEthConversion()
                        .via("constructorCreate")
                        .maxAutomaticTokenAssociations(5)
                        .hasKnownStatus(SUCCESS),
                contractCall(createContract, "create").via("createViaCall").hasKnownStatus(SUCCESS),
                ethereumCall(createContract, "create")
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("ethereumCreate")
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                getContractInfo(initCreateContract)
                        .has(contractWith().maxAutoAssociations(0))
                        .logged(),
                getContractInfo(multiPurpose)
                        .has(contractWith().maxAutoAssociations(3))
                        .logged(),
                getContractInfo(slotUserContract)
                        .has(contractWith().maxAutoAssociations(5))
                        .logged(),
                assertCreationMaxAssociations("constructorWithoutExplicitAssociations", 1, 0),
                assertCreationMaxAssociations("constructorCreate", 1, 5),
                assertCreationViaCallMaxAssociations("createViaCall", 0, 0),
                assertCreationViaCallMaxAssociations("ethereumCreate", 0, 0));
    }

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> childCreationsHaveExpectedKeysWithOmittedAdminKey() {
        final AtomicLong firstStickId = new AtomicLong();
        final AtomicLong secondStickId = new AtomicLong();
        final AtomicLong thirdStickId = new AtomicLong();
        final var txn = "creation";
        final var contract = "Fuse";

        return hapiTest(
                overriding("contracts.evm.version", "v0.46"),
                uploadInitCode(contract),
                contractCreate(contract).omitAdminKey().gas(600_000).via(txn),
                withOpContext((spec, opLog) -> {
                    final var op = getTxnRecord(txn);
                    allRunFor(spec, op);
                    final var record = op.getResponseRecord();
                    final var creationResult = record.getContractCreateResult();
                    final var createdIds = creationResult.getCreatedContractIDsList();
                    assertEquals(4, createdIds.size(), "Expected four creations but got " + createdIds);
                    firstStickId.set(createdIds.get(1).getContractNum());
                    secondStickId.set(createdIds.get(2).getContractNum());
                    thirdStickId.set(createdIds.get(3).getContractNum());
                }),
                sourcing(() -> getContractInfo("0.0." + firstStickId.get())
                        .has(contractWith().immutableContractKey("0.0." + firstStickId.get()))
                        .logged()),
                sourcing(() -> getContractInfo("0.0." + secondStickId.get())
                        .has(contractWith().immutableContractKey("0.0." + secondStickId.get()))
                        .logged()),
                sourcing(() -> getContractInfo("0.0." + thirdStickId.get()).logged()),
                contractCall(contract, "light").via("lightTxn"),
                sourcing(() -> getContractInfo("0.0." + firstStickId.get())
                        .has(contractWith().isDeleted())),
                sourcing(() -> getContractInfo("0.0." + secondStickId.get())
                        .has(contractWith().isDeleted())),
                sourcing(() -> getContractInfo("0.0." + thirdStickId.get())
                        .has(contractWith().isDeleted())));
    }

    @HapiTest
    final Stream<DynamicTest> createEmptyConstructor() {
        return hapiTest(uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT), contractCreate(EMPTY_CONSTRUCTOR_CONTRACT));
    }

    @HapiTest
    final Stream<DynamicTest> createCallInConstructor() {
        final var txn = "txn";
        return hapiTest(
                uploadInitCode("CallInConstructor"),
                contractCreate("CallInConstructor").via(txn).hasKnownStatus(SUCCESS),
                getTxnRecord(txn).logged(),
                withOpContext((spec, opLog) -> {
                    final var op = getTxnRecord(txn);
                    allRunFor(spec, op);
                    final var record = op.getResponseRecord();
                    final var creationResult = record.getContractCreateResult();
                    final var createdIds = creationResult.getCreatedContractIDsList();
                    assertEquals(1, createdIds.size(), "Expected one creations but got " + createdIds);
                    assertTrue(
                            createdIds.get(0).getContractNum() < 10000,
                            "Expected contract num < 10000 but got " + createdIds);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> revertedTryExtCallHasNoSideEffects() {
        final var balance = 3_000;
        final int sendAmount = balance / 3;
        final var contract = "RevertingSendTry";
        final var aBeneficiary = "aBeneficiary";
        final var bBeneficiary = "bBeneficiary";
        final var txn = "txn";

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).balance(balance),
                cryptoCreate(aBeneficiary).balance(0L),
                cryptoCreate(bBeneficiary).balance(0L),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var aNum = (int) registry.getAccountID(aBeneficiary).getAccountNum();
                    final var bNum = (int) registry.getAccountID(bBeneficiary).getAccountNum();
                    final var sendArgs =
                            new Object[] {Long.valueOf(sendAmount), Long.valueOf(aNum), Long.valueOf(bNum)};

                    final var op = contractCall(contract, "sendTo", sendArgs)
                            .gas(110_000)
                            .via(txn);
                    allRunFor(spec, op);
                }),
                getTxnRecord(txn),
                getAccountBalance(aBeneficiary),
                getAccountBalance(bBeneficiary));
    }

    @HapiTest
    final Stream<DynamicTest> createFailsIfMissingSigs() {
        final var shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        final var validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
        final var invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .adminKeyShape(shape)
                        .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, invalidSig))
                        .hasKnownStatus(INVALID_SIGNATURE)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .adminKeyShape(shape)
                        .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, validSig))
                        .hasKnownStatus(SUCCESS)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInsufficientGas() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                // refuse eth conversion because ethereum transaction fails in IngestChecker with precheck status
                // INSUFFICIENT_GAS
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .gas(0L)
                        .hasPrecheck(INSUFFICIENT_GAS)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> rejectsNegativeGas() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                cryptoCreate(PAYER), // need to use a payer that is not throttle_exempt
                // refuse eth conversion because ethereum transaction fails in IngestChecker with precheck status
                // INSUFFICIENT_GAS
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .gas(-50L)
                        .payingWith(PAYER)
                        .hasPrecheck(BUSY)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInvalidMemo() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .entityMemo(TxnUtils.nAscii(101))
                        .hasPrecheck(MEMO_TOO_LONG),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .entityMemo(ZERO_BYTE_MEMO)
                        .hasPrecheck(INVALID_ZERO_BYTE_IN_STRING));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInsufficientFee() {
        return hapiTest(
                cryptoCreate(PAYER),
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .payingWith(PAYER)
                        .fee(1L)
                        .hasPrecheck(INSUFFICIENT_TX_FEE));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsInvalidBytecode() {
        final var contract = "InvalidBytecode";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum transaction
                contractCreate(contract)
                        .hasKnownStatus(ERROR_DECODING_BYTESTRING)
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> revertsNonzeroBalance() {
        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                contractCreate(EMPTY_CONSTRUCTOR_CONTRACT).balance(1L).hasKnownStatus(CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    final Stream<DynamicTest> delegateContractIdRequiredForTransferInDelegateCall() {
        final var justSendContract = "JustSend";
        final var sendInternalAndDelegateContract = "SendInternalAndDelegate";

        final var beneficiary = "civilian";
        final var totalToSend = 1_000L;
        final var origKey = KeyShape.threshOf(1, SIMPLE, CONTRACT);
        final var revisedKey = KeyShape.threshOf(1, SIMPLE, DELEGATE_CONTRACT);
        final var newKey = "delegateContractKey";

        final AtomicLong justSendContractNum = new AtomicLong();
        final AtomicLong beneficiaryAccountNum = new AtomicLong();

        return hapiTest(
                uploadInitCode(justSendContract, sendInternalAndDelegateContract),
                // refuse eth conversion because we can't delegate call contract by contract num
                // when it has EVM address alias (isNotPriority check fails)
                contractCreate(justSendContract)
                        .gas(300_000L)
                        .exposingNumTo(justSendContractNum::set)
                        .refusingEthConversion(),
                contractCreate(sendInternalAndDelegateContract).gas(300_000L).balance(2 * totalToSend),
                cryptoCreate(beneficiary)
                        .balance(0L)
                        .keyShape(origKey.signedWith(sigs(ON, sendInternalAndDelegateContract)))
                        .receiverSigRequired(true)
                        .exposingCreatedIdTo(id -> beneficiaryAccountNum.set(id.getAccountNum())),
                /* Without delegateContractId permissions, the second send via delegate call will
                 * fail, so only half of totalToSend will make it to the beneficiary. (Note the entire
                 * call doesn't fail because exceptional halts in "raw calls" don't automatically
                 * propagate up the stack like a Solidity revert does.) */
                sourcing(() -> contractCall(
                        sendInternalAndDelegateContract,
                        "sendRepeatedlyTo",
                        BigInteger.valueOf(justSendContractNum.get()),
                        BigInteger.valueOf(beneficiaryAccountNum.get()),
                        BigInteger.valueOf(totalToSend / 2))),
                getAccountBalance(beneficiary).hasTinyBars(totalToSend / 2),
                /* But now we update the beneficiary to have a delegateContractId */
                newKeyNamed(newKey).shape(revisedKey.signedWith(sigs(ON, sendInternalAndDelegateContract))),
                cryptoUpdate(beneficiary).key(newKey),
                sourcing(() -> contractCall(
                        sendInternalAndDelegateContract,
                        "sendRepeatedlyTo",
                        BigInteger.valueOf(justSendContractNum.get()),
                        BigInteger.valueOf(beneficiaryAccountNum.get()),
                        BigInteger.valueOf(totalToSend / 2))),
                getAccountBalance(beneficiary).hasTinyBars(3 * (totalToSend / 2)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotCreateTooLargeContract() {
        ByteString contents;
        try {
            contents = ByteString.copyFrom(Files.readAllBytes(Path.of(bytecodePath("CryptoKitties"))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        final var FILE_KEY = "fileKey";
        final var KEY_LIST = "keyList";
        final var ACCOUNT = "acc";
        return hapiTest(
                newKeyNamed(FILE_KEY),
                newKeyListNamed(KEY_LIST, List.of(FILE_KEY)),
                cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS * 10).key(FILE_KEY),
                fileCreate("bytecode")
                        .path(bytecodePath("CryptoKitties"))
                        .hasPrecheck(TRANSACTION_OVERSIZE)
                        .orUnavailableStatus(),
                fileCreate("bytecode").contents("").key(KEY_LIST),
                UtilVerbs.updateLargeFile(ACCOUNT, "bytecode", contents),
                contractCreate("contract")
                        .bytecode("bytecode")
                        .payingWith(ACCOUNT)
                        .hasKnownStatus(INSUFFICIENT_GAS)
                        // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                        // transaction
                        .refusingEthConversion());
    }

    @HapiTest
    final Stream<DynamicTest> blockTimestampChangesWithinFewSeconds() {
        final var contract = "EmitBlockTimestamp";
        final var firstBlock = "firstBlock";
        final var timeLoggingTxn = "timeLoggingTxn";

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "logNow").via(firstBlock),
                cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(GENESIS, FUNDING, 1)),
                sleepFor(3_000),
                contractCall(contract, "logNow").via(timeLoggingTxn),
                withOpContext((spec, opLog) -> {
                    final var firstBlockOp = getTxnRecord(firstBlock);
                    final var recordOp = getTxnRecord(timeLoggingTxn);
                    allRunFor(spec, firstBlockOp, recordOp);

                    // First block info
                    final var firstBlockRecord = firstBlockOp.getResponseRecord();
                    final var firstBlockLogs =
                            firstBlockRecord.getContractCallResult().getLogInfoList();
                    final var firstBlockTimeLogData =
                            firstBlockLogs.get(0).getData().toByteArray();
                    final var firstBlockTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(firstBlockTimeLogData, 24, 32));
                    final var firstBlockHashLogData =
                            firstBlockLogs.get(1).getData().toByteArray();
                    final var firstBlockNumber = Longs.fromByteArray(Arrays.copyOfRange(firstBlockHashLogData, 24, 32));
                    final var firstBlockHash = Bytes32.wrap(Arrays.copyOfRange(firstBlockHashLogData, 32, 64));
                    assertEquals(Bytes32.ZERO, firstBlockHash);

                    // Second block info
                    final var secondBlockRecord = recordOp.getResponseRecord();
                    final var secondBlockLogs =
                            secondBlockRecord.getContractCallResult().getLogInfoList();
                    assertEquals(2, secondBlockLogs.size());
                    final var secondBlockTimeLogData =
                            secondBlockLogs.get(0).getData().toByteArray();
                    final var secondBlockTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(secondBlockTimeLogData, 24, 32));
                    assertNotEquals(firstBlockTimestamp, secondBlockTimestamp, "Block timestamps should change");

                    final var secondBlockHashLogData =
                            secondBlockLogs.get(1).getData().toByteArray();
                    final var secondBlockNumber =
                            Longs.fromByteArray(Arrays.copyOfRange(secondBlockHashLogData, 24, 32));
                    assertNotEquals(firstBlockNumber, secondBlockNumber, "Wrong previous block number");
                    final var secondBlockHash = Bytes32.wrap(Arrays.copyOfRange(secondBlockHashLogData, 32, 64));

                    assertEquals(Bytes32.ZERO, secondBlockHash);
                }),
                contractCallLocal(contract, "getLastBlockHash")
                        .exposingTypedResultsTo(
                                results -> log.info("Results were {}", CommonUtils.hex((byte[]) results[0]))));
    }

    @HapiTest
    final Stream<DynamicTest> tryContractCreateWithMaxAutoAssoc() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(-2)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(-200000)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(-1)
                        .hasKnownStatus(SUCCESS),
                getContractInfo(contract)
                        .has(contractWith().maxAutoAssociations(-1))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> tryContractCreateWithZeroAutoAssoc() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(0)
                        .hasKnownStatus(SUCCESS),
                getContractInfo(contract)
                        .has(contractWith().maxAutoAssociations(0))
                        .logged());
    }

    @HapiTest
    final Stream<DynamicTest> tryContractCreateWithBalance() {
        final var contract = "Donor";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .balance(ONE_HUNDRED_HBARS)
                        .hasKnownStatus(SUCCESS),
                getContractInfo(contract)
                        .has(contractWith().maxAutoAssociations(0).balance(ONE_HUNDRED_HBARS))
                        .logged());
    }

    final Stream<DynamicTest> contractCreateShouldChargeTheSame() {
        final var createFeeWithMaxAutoAssoc = 10L;
        final var contract1 = "EmptyOne";
        final var contract2 = "EmptyTwo";
        return hapiTest(
                uploadInitCode(contract1),
                uploadInitCode(contract2),
                contractCreate(contract1)
                        .via(contract1)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(1)
                        .hasKnownStatus(SUCCESS),
                contractCreate(contract2)
                        .via(contract2)
                        .adminKey(THRESHOLD)
                        .refusingEthConversion()
                        .maxAutomaticTokenAssociations(1000)
                        .hasKnownStatus(SUCCESS),
                getContractInfo(contract1)
                        .has(contractWith().maxAutoAssociations(1))
                        .logged(),
                getTxnRecord(contract1).fee(createFeeWithMaxAutoAssoc).logged(),
                getContractInfo(contract2)
                        .has(contractWith().maxAutoAssociations(1000))
                        .logged(),
                getTxnRecord(contract2).fee(createFeeWithMaxAutoAssoc).logged());
    }

    @HapiTest
    final Stream<DynamicTest> vanillaSuccess() {
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing admin key
                contractCreate(contract).adminKey(THRESHOLD).refusingEthConversion(),
                getContractInfo(contract).saveToRegistry(PARENT_INFO),
                contractCall(contract, "create").gas(1_000_000L).via("createChildTxn"),
                contractCall(contract, "getIndirect").gas(1_000_000L).via("getChildResultTxn"),
                contractCall(contract, "getAddress").gas(1_000_000L).via("getChildAddressTxn"),
                getTxnRecord("createChildTxn")
                        .saveCreatedContractListToRegistry("createChild")
                        .logged(),
                getTxnRecord("getChildResultTxn")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getIndirect", contract),
                                                isLiteralResult(new Object[] {BigInteger.valueOf(7L)})))),
                getTxnRecord("getChildAddressTxn")
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "getAddress", contract),
                                                isContractWith(contractWith()
                                                        .nonNullContractId()
                                                        .propertiesInheritedFrom(PARENT_INFO)))
                                        .logs(inOrder()))),
                contractListWithPropertiesInheritedFrom("createChildCallResult", 1, PARENT_INFO));
    }

    @HapiTest
    final Stream<DynamicTest> newAccountsCanUsePureContractIdKey() {
        final var contract = "CreateTrivial";
        final var contractControlled = "contractControlled";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(contractControlled).keyShape(CONTRACT.signedWith(contract)),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var contractIdKey = Key.newBuilder()
                            .setContractID(registry.getContractId(contract))
                            .build();
                    final var keyCheck =
                            getAccountInfo(contractControlled).has(accountWith().key(contractIdKey));
                    allRunFor(spec, keyCheck);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var autoRenewAccount = "autoRenewAccount";
        final var creationNumber = new AtomicLong();
        final var contract = "CreateTrivial";
        return hapiTest(
                uploadInitCode(contract),
                cryptoCreate(autoRenewAccount).balance(ONE_HUNDRED_HBARS),
                submitModified(withSuccessivelyVariedBodyIds(), () -> contractCreate(
                                "contract" + creationNumber.getAndIncrement())
                        .bytecode(contract)
                        .autoRenewAccountId(autoRenewAccount)));
    }

    @HapiTest
    final Stream<DynamicTest> contractWithAutoRenewNeedSignatures() {
        final var contract = "CreateTrivial";
        final var autoRenewAccount = "autoRenewAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(contract),
                cryptoCreate(autoRenewAccount).balance(ONE_HUNDRED_HBARS),
                // refuse eth conversion because ethereum transaction is missing autoRenewAccountId field to map
                contractCreate(contract)
                        .adminKey(ADMIN_KEY)
                        .autoRenewAccountId(autoRenewAccount)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE)
                        .refusingEthConversion(),
                contractCreate(contract)
                        .adminKey(ADMIN_KEY)
                        .autoRenewAccountId(autoRenewAccount)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, autoRenewAccount)
                        .refusingEthConversion()
                        .logged(),
                getContractInfo(contract).has(ContractInfoAsserts.contractWith().maxAutoAssociations(0)));
    }

    private EthTxData placeholderEthTx() {
        return new EthTxData(
                null,
                EthTxData.EthTransactionType.EIP1559,
                Integers.toBytes(CHAIN_ID),
                0L,
                BigInteger.ONE.toByteArray(),
                BigInteger.ONE.toByteArray(),
                BigInteger.ONE.toByteArray(),
                150_000,
                new byte[] {1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4},
                BigInteger.ONE,
                new byte[] {},
                new byte[] {},
                0,
                null,
                null,
                null);
    }
}
