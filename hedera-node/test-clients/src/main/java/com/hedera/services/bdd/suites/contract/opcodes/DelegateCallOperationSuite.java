// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class DelegateCallOperationSuite {
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistence() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "delegateCall", asHeadlongAddress(INVALID_ADDRESS))
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var id = spec.registry().getAccountID(DEFAULT_PAYER);
                    final var solidityAddress = asHexedSolidityAddress(id);

                    final var contractCall = contractCall(contract, "delegateCall", asHeadlongAddress(solidityAddress))
                            .hasKnownStatus(SUCCESS);

                    allRunFor(spec, contractCall);
                }));
    }
}
