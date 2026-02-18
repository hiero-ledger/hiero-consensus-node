// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransferToExplicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCodeWithConstructorArguments;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_HASH_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_SENDER_ADDRESS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.CONSTRUCTOR;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.queries.meta.AccountCreationDetails;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class HelloWorldEthereumSuite {
    public static final long depositAmount = 20_000L;

    private static final String PAY_RECEIVABLE_CONTRACT = "PayReceivable";
    private static final String TOKEN_CREATE_CONTRACT = "TokenCreateContract";
    private static final String OC_TOKEN_CONTRACT = "OcToken";
    private static final String CALLDATA_SIZE_CONTRACT = "CalldataSize";
    private static final String BLOCKQUERIES_CONTRACT = "BlockQueries";
    private static final String DEPOSIT = "deposit";

    @HapiTest
    final Stream<DynamicTest> canCreateTokenWithCryptoAdminKeyOnlyIfHasTopLevelSig() {
        final var cryptoKey = "cryptoKey";
        final var thresholdKey = "thresholdKey";
        final String contract = "TestTokenCreateContract";
        final AtomicReference<byte[]> adminKey = new AtomicReference<>();
        final AtomicReference<AccountCreationDetails> creationDetails = new AtomicReference<>();

        return hapiTest(
                // Deploy our test contract
                uploadInitCode(contract),
                contractCreate(contract).gas(5_000_000L),

                // Create an ECDSA key
                newKeyNamed(cryptoKey)
                        .shape(SECP256K1_ON)
                        .exposingKeyTo(k -> adminKey.set(k.getECDSASecp256K1().toByteArray())),
                // Create an account with an EVM address derived from this key
                cryptoTransfer(tinyBarsFromToWithAlias(DEFAULT_PAYER, cryptoKey, 2 * ONE_HUNDRED_HBARS))
                        .via("creation"),
                // Get its EVM address for later use in the contract call
                getTxnRecord("creation")
                        .exposingCreationDetailsTo(allDetails -> creationDetails.set(allDetails.getFirst())),
                // Update key to a threshold key authorizing our contract use this account as a token treasury
                newKeyNamed(thresholdKey)
                        .shape(threshOf(1, PREDEFINED_SHAPE, CONTRACT).signedWith(sigs(cryptoKey, contract))),
                sourcing(
                        () -> cryptoUpdate(asAccountString(creationDetails.get().createdId()))
                                .key(thresholdKey)
                                .signedBy(DEFAULT_PAYER, cryptoKey)),
                // First verify we fail to create without the admin key's top-level signature
                sourcing(() -> contractCall(
                                contract,
                                "createFungibleTokenWithSECP256K1AdminKeyPublic",
                                // Treasury is the EVM address
                                creationDetails.get().evmAddress(),
                                // Admin key is the ECDSA key
                                adminKey.get())
                        .via("creationWithoutTopLevelSig")
                        .gas(5_000_000L)
                        .sending(100 * ONE_HBAR)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                // Next verify we succeed when using the top-level SignatureMap to
                // sign with the admin key
                sourcing(() -> contractCall(
                                contract,
                                "createFungibleTokenWithSECP256K1AdminKeyPublic",
                                creationDetails.get().evmAddress(),
                                adminKey.get())
                        .via("creationActivatingAdminKeyViaSigMap")
                        .gas(5_000_000L)
                        .sending(100 * ONE_HBAR)
                        // This is the important change, include a top-level signature with the admin key
                        .alsoSigningWithFullPrefix(cryptoKey)),
                // Finally confirm we ALSO succeed when providing the admin key's
                // signature via an EthereumTransaction signature
                cryptoCreate(RELAYER).balance(10 * THOUSAND_HBAR),
                sourcing(() -> ethereumCall(
                                contract,
                                "createFungibleTokenWithSECP256K1AdminKeyPublic",
                                creationDetails.get().evmAddress(),
                                adminKey.get())
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .nonce(0)
                        .signingWith(cryptoKey)
                        .payingWith(RELAYER)
                        .sending(50 * ONE_HBAR)
                        .maxGasAllowance(ONE_HBAR * 10)
                        .gasLimit(5_000_000L)
                        .via("creationActivatingAdminKeyViaEthTxSig")),
                childRecordsCheck(
                        "creationWithoutTopLevelSig",
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                getTxnRecord("creationActivatingAdminKeyViaSigMap")
                        .exposingTokenCreationsTo(
                                createdIds -> assertFalse(createdIds.isEmpty(), "Top-level sig map creation failed")),
                getTxnRecord("creationActivatingAdminKeyViaEthTxSig")
                        .exposingTokenCreationsTo(
                                createdIds -> assertFalse(createdIds.isEmpty(), "EthTx sig creation failed")));
    }

    @HapiTest
    final Stream<DynamicTest> badRelayClient() {
        final var adminKey = "adminKey";
        final var exploitToken = "exploitToken";
        final var exploitContract = "BadRelayClient";
        final var maliciousTxn = "theft";
        final var maliciousEOA = "maliciousEOA";
        final var maliciousAutoCreation = "maliciousAutoCreation";
        final var maliciousStartBalance = ONE_HUNDRED_HBARS;
        final AtomicReference<String> maliciousEOAId = new AtomicReference<>();
        final AtomicReference<String> relayerEvmAddress = new AtomicReference<>();
        final AtomicReference<String> exploitTokenEvmAddress = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(adminKey),
                newKeyNamed(maliciousEOA).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER)
                        .balance(10 * ONE_MILLION_HBARS)
                        .exposingCreatedIdTo(id -> relayerEvmAddress.set(asHexedSolidityAddress(id))),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, maliciousEOA, maliciousStartBalance))
                        .via(maliciousAutoCreation),
                withOpContext((spec, opLog) -> {
                    final var lookup = getTxnRecord(maliciousAutoCreation)
                            .andAllChildRecords()
                            .logged();
                    allRunFor(spec, lookup);
                    final var childCreation = lookup.getFirstNonStakingChildRecord();
                    maliciousEOAId.set(
                            asAccountString(childCreation.getReceipt().getAccountID()));
                }),
                uploadInitCode(exploitContract),
                contractCreate(exploitContract).adminKey(adminKey),
                sourcing(() -> tokenCreate(exploitToken)
                        .treasury(maliciousEOAId.get())
                        .symbol("IDYM")
                        .symbol("I DRINK YOUR MILKSHAKE")
                        .initialSupply(Long.MAX_VALUE)
                        .decimals(0)
                        .withCustom(fixedHbarFee(ONE_MILLION_HBARS, maliciousEOAId.get()))
                        .signedBy(DEFAULT_PAYER, maliciousEOA)
                        .exposingCreatedIdTo(id -> exploitTokenEvmAddress.set(
                                asHexedSolidityAddress(0, 0, asToken(id).getTokenNum())))),
                sourcing(() -> ethereumCall(
                                exploitContract,
                                "stealFrom",
                                asHeadlongAddress(relayerEvmAddress.get()),
                                asHeadlongAddress(exploitTokenEvmAddress.get()))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(maliciousEOA)
                        .payingWith(RELAYER)
                        .via(maliciousTxn)
                        .nonce(0)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                getTxnRecord(maliciousTxn).andAllChildRecords().logged(),
                childRecordsCheck(
                        maliciousTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)),
                sourcing(() -> getAccountBalance(maliciousEOAId.get())
                        .hasTinyBars(spec -> amount -> (amount > maliciousStartBalance)
                                ? Optional.of("Malicious" + " EOA balance" + " increased")
                                : Optional.empty())),
                getAliasedAccountInfo(maliciousEOA).has(accountWith().nonce(1L)));
    }

    @HapiTest
    final Stream<DynamicTest> badRecIdGivesInvalidSignature() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                // EIP1559 Ethereum Calls fail with invalid rec ids
                ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("payTxn")
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount)
                        .withWrongParityRecId()
                        .hasKnownStatus(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> depositSuccess() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                // EIP1559 Ethereum Calls Work
                ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("payTxn")
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount)
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                // Legacy Ethereum Calls Work
                ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                        .type(EthTxData.EthTransactionType.LEGACY_ETHEREUM)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("payTxn")
                        .nonce(1)
                        .gasPrice(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount)
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                // Ethereum Call with FileID callData works
                ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("payTxn")
                        .nonce(2)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount)
                        .createCallDataFile()
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("payTxn")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8())))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(3L)));
    }

    @HapiTest
    final Stream<DynamicTest> ethereumCallWithCalldataBiggerThanMaxSucceeds() {
        final var largerThanMaxCalldata = new byte[MAX_CALL_DATA_SIZE + 1];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(CALLDATA_SIZE_CONTRACT),
                contractCreate(CALLDATA_SIZE_CONTRACT).adminKey(THRESHOLD),
                ethereumCall(CALLDATA_SIZE_CONTRACT, "callme", largerThanMaxCalldata)
                        .via("payTxn")
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("payTxn")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder(logWith()
                                                        .longAtBytes(largerThanMaxCalldata.length, 24)
                                                        .withTopicsInOrder(List.of(eventSignatureOf("Info(uint256)")))))
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8())))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)));
    }

    @HapiTest
    final Stream<DynamicTest> customizedEvmValuesAreCustomized() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(BLOCKQUERIES_CONTRACT),
                contractCreate(BLOCKQUERIES_CONTRACT).adminKey(THRESHOLD),
                // Blobbasefee set correctly initially:
                ethereumCall(BLOCKQUERIES_CONTRACT, "getBlobBaseFee")
                        .via("callTxn1")
                        .hasKnownStatus(SUCCESS),
                // Blobbasefee propagates to child frames correctly:
                ethereumCall(BLOCKQUERIES_CONTRACT, "getBlobBaseFeeR", BigInteger.TEN)
                        .via("callTxn2")
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("callTxn1")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder(logWith()
                                                        .longAtBytes(1L /* i.e., 1 Wei */, 24)
                                                        .withTopicsInOrder(
                                                                List.of(eventSignatureOf("Info(uint256)"))))))),
                        getTxnRecord("callTxn2")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder(logWith()
                                                        .longAtBytes(1L /* i.e., 1 Wei */, 24)
                                                        .withTopicsInOrder(
                                                                List.of(eventSignatureOf("Info(uint256)"))))))))));
    }

    @HapiTest
    final Stream<DynamicTest> createWithSelfDestructInConstructorHasSaneRecord() {
        final var txn = "txn";
        final var selfDestructingContract = "FactorySelfDestructConstructor";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                uploadInitCode(selfDestructingContract),
                ethereumContractCreate(selfDestructingContract)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(5_000_000L)
                        .via(txn),
                childRecordsCheck(
                        txn,
                        SUCCESS,
                        recordWith().hasMirrorIdInReceipt(),
                        recordWith().hasMirrorIdInReceipt()));
    }

    @HapiTest
    final Stream<DynamicTest> smallContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                ethereumContractCreate(PAY_RECEIVABLE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(SUCCESS)
                        .via("payTxn"),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("payTxn")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCreateResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8()))
                                                .create1EvmAddress(
                                                        ByteString.copyFrom(
                                                                spec.registry().getBytes(ETH_SENDER_ADDRESS)),
                                                        0L))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)));
    }

    @HapiTest
    final Stream<DynamicTest> doesNotCreateChildRecordIfEthereumContractCreateFails() {
        final Long insufficientGasAllowance = 1L;
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                ethereumContractCreate(PAY_RECEIVABLE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(insufficientGasAllowance)
                        .gasLimit(1_000_000L)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via("insufficientTxFeeTxn"),
                getTxnRecord("insufficientTxFeeTxn").andAllChildRecords().hasNonStakingChildRecordCount(0));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var contractAdminKey = "contractAdminKey";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                newKeyNamed(contractAdminKey),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                submitModified(withSuccessivelyVariedBodyIds(), () -> ethereumContractCreate(TOKEN_CREATE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(4_000_000L)));
    }

    @HapiTest
    final Stream<DynamicTest> bigContractCreate() {
        final var contractAdminKey = "contractAdminKey";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                newKeyNamed(contractAdminKey),
                uploadInitCode(TOKEN_CREATE_CONTRACT),
                ethereumContractCreate(TOKEN_CREATE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(4_000_000L)
                        .via("payTxn")
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("payTxn")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCreateResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8()))
                                                .create1EvmAddress(
                                                        ByteString.copyFrom(
                                                                spec.registry().getBytes(ETH_SENDER_ADDRESS)),
                                                        0L))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithConstructorArgs() {
        final var contractAdminKey = "contractAdminKey";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                newKeyNamed(contractAdminKey),
                uploadInitCodeWithConstructorArguments(
                        OC_TOKEN_CONTRACT,
                        getABIFor(CONSTRUCTOR, EMPTY, OC_TOKEN_CONTRACT),
                        BigInteger.valueOf(1_000_000L),
                        "OpenCrowd Token",
                        "OCT"),
                ethereumContractCreate(OC_TOKEN_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasPrice(10L)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(1_000_000L)
                        .via("payTxn")
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("payTxn")
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCreateResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8()))
                                                .create1EvmAddress(
                                                        ByteString.copyFrom(
                                                                spec.registry().getBytes(ETH_SENDER_ADDRESS)),
                                                        0L))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)));
    }

    private static final String JUST_SEND_CONTRACT = "JustSend";

    private static final String SEND_TO = "sendTo";

    @HapiTest
    final Stream<DynamicTest> topLevelBurnToZeroAddressReverts() {
        final var ethBurnAddress = new byte[20];
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(123 * ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                ethereumCryptoTransferToExplicit(ethBurnAddress, 123)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .hasPrecheck(INVALID_SOLIDITY_ADDRESS));
    }

    @HapiTest
    final Stream<DynamicTest> topLevelLazyCreateOfMirrorAddressReverts() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(123 * ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                withOpContext((spec, opLog) -> ethereumCryptoTransferToExplicit(asSolidityAddress(spec, 666_666), 123)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .hasPrecheck(INVALID_ALIAS_KEY)));
    }

    @HapiTest
    final Stream<DynamicTest> topLevelSendToReceiverSigRequiredAccountReverts() {
        final var receiverSigAccount = "receiverSigAccount";
        final AtomicReference<byte[]> receiverMirrorAddr = new AtomicReference<>();
        final var preCallBalance = "preCallBalance";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(123 * ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(receiverSigAccount)
                        .receiverSigRequired(true)
                        .exposingCreatedIdTo(id -> receiverMirrorAddr.set(asSolidityAddress(id))),
                uploadInitCode(JUST_SEND_CONTRACT),
                contractCreate(JUST_SEND_CONTRACT),
                balanceSnapshot(preCallBalance, receiverSigAccount),
                sourcing(() -> ethereumCryptoTransferToExplicit(receiverMirrorAddr.get(), 123)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .hasPrecheck(INVALID_SIGNATURE)),
                getAccountBalance(receiverSigAccount).hasTinyBars(changeFromSnapshot(preCallBalance, 0L)));
    }

    @HapiTest
    final Stream<DynamicTest> internalBurnToZeroAddressReverts() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(123 * ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(JUST_SEND_CONTRACT),
                contractCreate(JUST_SEND_CONTRACT),
                ethereumCall(JUST_SEND_CONTRACT, SEND_TO, BigInteger.ZERO, BigInteger.valueOf(123))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount)
                        .hasKnownStatus(INVALID_CONTRACT_ID));
    }
}
