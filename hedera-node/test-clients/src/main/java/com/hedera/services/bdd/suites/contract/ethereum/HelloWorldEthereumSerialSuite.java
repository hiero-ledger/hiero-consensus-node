// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
public class HelloWorldEthereumSerialSuite {

    private static final String BLOCKQUERIES_CONTRACT = "BlockQueries";

    @LeakyHapiTest
    final Stream<DynamicTest> customizedEvmValuesAreCustomized() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(BLOCKQUERIES_CONTRACT),
                contractCreate(BLOCKQUERIES_CONTRACT).adminKey(THRESHOLD),
                ethereumCall(BLOCKQUERIES_CONTRACT, "getBlobBaseFee")
                        .via("callTxn1")
                        .hasKnownStatus(SUCCESS),
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
}
