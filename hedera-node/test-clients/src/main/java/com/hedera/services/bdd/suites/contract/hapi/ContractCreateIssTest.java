// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.TargetNetworkType;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractCreateIssTest {

    private static final ByteString INIT_CODE = ByteString.fromHex("60ef6000536160016000f3"); // size and prefix

    @HapiTest
    Stream<DynamicTest> contractCreateIssTest() {
        final var cycles = 10;
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                withOpContext((spec, opLog) -> {
                    Assertions.assertEquals(
                            TargetNetworkType.SUBPROCESS_NETWORK,
                            spec.targetNetworkType(),
                            "This ISS test requires a subprocess (multi-node) network");
                    final var nodes = spec.targetNetworkOrThrow().nodes();
                    Assertions.assertTrue(nodes.size() > 1); // check we are on a multi-node env
                    opLog.info("Running with {} nodes", nodes.size());
                    // run cycles to each node
                    for (int i = 0; i < cycles; i++) {
                        final var txnName = "issTestContract-" + i;
                        final var create = contractCreate("IssTestContract")
                                .gas(15_000_000)
                                .entityMemo("IssTestContract")
                                .inlineInitCode(INIT_CODE)
                                .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                                .via(txnName)
                                .refusingEthConversion();
                        allRunFor(spec, create);
                        // error message is new for each cycle to confirm ISS is happening on specific cycle but not
                        // between them
                        final AtomicReference<String> error = new AtomicReference<>();
                        for (var node : nodes) {
                            allRunFor(
                                    spec,
                                    getTxnRecord(txnName)
                                            .setNode(Long.toString(
                                                    node.getAccountId().accountNum()))
                                            .exposingTo(record -> {
                                                final var currentErrorMessage = record.getContractCreateResult()
                                                        .getErrorMessage();
                                                if (error.get() == null) {
                                                    error.set(currentErrorMessage);
                                                } else {
                                                    Assertions.assertEquals(error.get(), currentErrorMessage);
                                                }
                                            }));
                        }
                    }
                }));
    }
}
