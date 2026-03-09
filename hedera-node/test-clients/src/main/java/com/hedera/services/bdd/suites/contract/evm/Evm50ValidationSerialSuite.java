// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
public class Evm50ValidationSerialSuite {
    private static final String MODULE_05_OPCODES_EXIST_CONTRACT = "Module050OpcodesExist";
    private static final long A_BUNCH_OF_GAS = 500_000L;

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    final Stream<DynamicTest> verifiesNonExistenceForV50OpcodesInV46() {
        return hapiTest(
                overriding("contracts.evm.version", "v0.46"),
                uploadInitCode(MODULE_05_OPCODES_EXIST_CONTRACT),
                contractCreate(MODULE_05_OPCODES_EXIST_CONTRACT),
                contractCall(MODULE_05_OPCODES_EXIST_CONTRACT, "try_transient_storage")
                        .gas(A_BUNCH_OF_GAS)
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION),
                contractCall(MODULE_05_OPCODES_EXIST_CONTRACT, "try_mcopy")
                        .gas(A_BUNCH_OF_GAS)
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION),
                contractCall(MODULE_05_OPCODES_EXIST_CONTRACT, "try_kzg_precompile")
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION));
    }
}
