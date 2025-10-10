// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.leaky.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of LeakyEthereumTestsSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960")
@HapiTestLifecycle
class AtomicLeakyEthereumTestsSuite {
    private static final String PAY_RECEIVABLE_CONTRACT = "PayReceivable";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("contracts.throttle.throttleByGas", "false", "cryptoCreateWithAlias.enabled", "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    // test unprotected legacy ethereum transactions before EIP155
    // this tests the behaviour when the `v` field is 27 or 28
    // in this case the passed chainId = 0 so ETX is before EIP155
    // and so `v` is calculated -> v = {0,1} + 27
    // source: https://eips.ethereum.org/EIPS/eip-155
    @LeakyHapiTest(overrides = {"contracts.chainId"})
    @Tag(MATS)
    final Stream<DynamicTest> legacyUnprotectedEtxBeforeEIP155() {
        final String DEPOSIT = "deposit";
        final long depositAmount = 20_000L;
        final Integer chainId = 0;

        return hapiTest(
                overriding("contracts.chainId", "" + chainId),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                atomicBatch(ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                                .type(EthTransactionType.LEGACY_ETHEREUM)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .via("legacyBeforeEIP155")
                                .nonce(0)
                                .chainId(chainId)
                                .gasPrice(50L)
                                .maxPriorityGas(2L)
                                .gasLimit(1_000_000L)
                                .sending(depositAmount)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("legacyBeforeEIP155")
                                .logged()
                                .hasPriority(recordWith().status(SUCCESS)))));
    }

    /**
     * test unprotected legacy ethereum transactions before EIP155
     * When using a `CHAIN_ID` represented as a BigInteger, an additional byte is required
     * to store the sign information (indicating whether the value is positive or negative),
     * if there is no free bit available for this information, as in values like 11155111.
     */
    @LeakyHapiTest(overrides = {"contracts.chainId"})
    @Tag(MATS)
    /* default */ final Stream<DynamicTest> legacyUnprotectedEtxBeforeEIP155WithChainIdHavingExtraByteForSign() {
        final var deposit = "deposit";
        final var depositAmount = 20_000L;
        final var chainId = 11_155_111;

        return hapiTest(
                overriding("contracts.chainId", "" + chainId),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                atomicBatch(ethereumCall(PAY_RECEIVABLE_CONTRACT, deposit, BigInteger.valueOf(depositAmount))
                                .type(EthTransactionType.LEGACY_ETHEREUM)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .via("legacyBeforeEIP155")
                                .nonce(0)
                                .chainId(chainId)
                                .gasPrice(50L)
                                .maxPriorityGas(2L)
                                .gasLimit(1_000_000L)
                                .sending(depositAmount)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("legacyBeforeEIP155")
                                .logged()
                                .hasPriority(recordWith().status(SUCCESS)))));
    }

    // test legacy ethereum transactions after EIP155
    // this tests the behaviour when the `v` field is 37 or 38
    // in this case the passed chainId = 1 so ETX is after EIP155
    // and so `v` is calculated -> v = {0,1} + CHAIN_ID * 2 + 35
    // source: https://eips.ethereum.org/EIPS/eip-155
    @LeakyHapiTest(overrides = {"contracts.chainId"})
    @Tag(MATS)
    final Stream<DynamicTest> legacyEtxAfterEIP155() {
        final String DEPOSIT = "deposit";
        final long depositAmount = 20_000L;
        final Integer chainId = 1;

        return hapiTest(
                overriding("contracts.chainId", "" + chainId),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                overriding("contracts.chainId", "" + chainId),
                atomicBatch(ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                                .type(EthTransactionType.LEGACY_ETHEREUM)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .via("legacyAfterEIP155")
                                .nonce(0)
                                .chainId(chainId)
                                .gasPrice(50L)
                                .maxPriorityGas(2L)
                                .gasLimit(1_000_000L)
                                .sending(depositAmount)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("legacyAfterEIP155")
                                .logged()
                                .hasPriority(recordWith().status(SUCCESS)))));
    }
}
