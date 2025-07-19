// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccountString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_HASH_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THOUSAND_HBAR;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.queries.meta.AccountCreationDetails;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchEthereumCallKeysTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    public final Stream<DynamicTest> canCreateTokenWithCryptoAdminKeyOnlyIfHasTopLevelSig() {
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
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                        contract,
                                        "createFungibleTokenWithSECP256K1AdminKeyPublic",
                                        // Treasury is the EVM address
                                        creationDetails.get().evmAddress(),
                                        // Admin key is the ECDSA key
                                        adminKey.get())
                                .via("creationWithoutTopLevelSig")
                                .gas(5_000_000L)
                                .sending(100 * ONE_HBAR)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                // Next verify we succeed when using the top-level SignatureMap to
                // sign with the admin key
                sourcing(() -> atomicBatchDefaultOperator(contractCall(
                                contract,
                                "createFungibleTokenWithSECP256K1AdminKeyPublic",
                                creationDetails.get().evmAddress(),
                                adminKey.get())
                        .via("creationActivatingAdminKeyViaSigMap")
                        .gas(5_000_000L)
                        .sending(100 * ONE_HBAR)
                        // This is the important change, include a top-level signature with the admin key
                        .alsoSigningWithFullPrefix(cryptoKey))),
                // Finally confirm we ALSO succeed when providing the admin key's
                // signature via an EthereumTransaction signature
                cryptoCreate(RELAYER).balance(10 * THOUSAND_HBAR),
                sourcing(() -> atomicBatchDefaultOperator(ethereumCall(
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
                        .via("creationActivatingAdminKeyViaEthTxSig"))),
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
    public final Stream<DynamicTest> precompileCallFailsWhenSignatureMissingFromBothEthereumAndHederaTxn() {
        final AtomicReference<TokenID> fungible = new AtomicReference<>();
        final String fungibleToken = "token";
        final String mintTxn = "mintTxn";
        final String autoAccountTransaction = "autoAccountTransaction";
        final String multiKey = "multiKey";
        final String helloWorldMint = "HelloWorldMint";
        return hapiTest(
                newKeyNamed(multiKey),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(autoAccountTransaction),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(autoAccountTransaction).andAllChildRecords(),
                uploadInitCode(helloWorldMint),
                tokenCreate(fungibleToken)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .adminKey(multiKey)
                        .supplyKey(multiKey)
                        .exposingCreatedIdTo(idLit -> fungible.set(asToken(idLit))),
                sourcing(() -> contractCreate(helloWorldMint, asHeadlongAddress(asAddress(fungible.get())))),
                atomicBatchDefaultOperator(ethereumCall(helloWorldMint, "brrr", BigInteger.valueOf(5))
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .nonce(0)
                                .via(mintTxn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(mintTxn)
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
                childRecordsCheck(
                        mintTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    private HapiAtomicBatch atomicBatchDefaultOperator(final HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }
}
