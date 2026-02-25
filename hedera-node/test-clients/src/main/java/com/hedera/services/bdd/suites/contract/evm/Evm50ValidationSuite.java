// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.evm;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class Evm50ValidationSuite {
    private static final String Module05OpcodesExist_CONTRACT = "Module050OpcodesExist";
    private static final long A_BUNCH_OF_GAS = 500_000L;

    @LeakyHapiTest(overrides = {"contracts.evm.version"})
    @Tag(MATS)
    final Stream<DynamicTest> verifiesNonExistenceForV50OpcodesInV46() {
        final var contract = Module05OpcodesExist_CONTRACT;
        return hapiTest(
                overriding("contracts.evm.version", "v0.46"),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "try_transient_storage")
                        .gas(A_BUNCH_OF_GAS)
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION),
                contractCall(contract, "try_mcopy").gas(A_BUNCH_OF_GAS).hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION),
                contractCall(contract, "try_kzg_precompile").hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistenceOfV050Opcodes() {
        final var contract = Module05OpcodesExist_CONTRACT;
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, "try_transient_storage").gas(A_BUNCH_OF_GAS),
                contractCall(contract, "try_mcopy").gas(A_BUNCH_OF_GAS),
                contractCall(contract, "try_kzg_precompile").gas(A_BUNCH_OF_GAS));
    }
}
